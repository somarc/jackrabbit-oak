import groovy.transform.CompileStatic
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import org.apache.jackrabbit.oak.api.PropertyState
import org.apache.jackrabbit.oak.api.Type
import org.apache.jackrabbit.oak.plugins.segment.SegmentBlob
import org.apache.jackrabbit.oak.spi.state.NodeState
import org.apache.jackrabbit.oak.spi.state.NodeStore
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry
import org.apache.jackrabbit.oak.console.ConsoleSession
import org.codehaus.groovy.tools.shell.CommandSupport
import org.codehaus.groovy.tools.shell.Groovysh

import java.util.Properties
import java.io.InputStream

/**
 * A Groovy command for the oak-run console to count nodes and binaries in a Jackrabbit Oak repository,
 * detect missing segments or blobs, and analyze corruption with AEM-specific recovery heuristics.
 * Optimized for large-scale traversal and crisis scenarios, with bounded collections to manage memory.
 *
 * <p>This command is executed within the restricted oak-run console (Oak 1.22.22) Groovy shell,
 * supporting operations on an AEM repository's SegmentStore (TarMK). It is not compatible with
 * DocumentNodeStore (Mongo/DocumentDB). Future oak-run console API restrictions may require updates.</p>
 *
 * <p><b>Performance Notes:</b></p>
 * <ul>
 *   <li><b>Recursion</b>: Uses depth-first recursion for node traversal; extremely deep trees may
 *       cause stack overflow, though rare in AEM repositories.</li>
 *   <li><b>I/O</b>: The <code>deep</code> option triggers I/O-intensive binary stream reading, controlled
 *       by a flag to avoid unintended performance impacts.</li>
 * </ul>
 *
 * <p><b>Limitations:</b></p>
 * <ul>
 *   <li><b>Evictions</b>: Bounded collections (<code>MAX_PATHS=10,000</code>) may evict paths in
 *       large corruption sets, logged as <code>Evictions: missingPaths=X</code>.</li>
 *   <li><b>Multi-Path SNFE</b>: Flags multi-path SegmentNotFoundException but does not compute minimal
 *       deletion sets or dependency graphs, recommending deletion of all affected paths.</li>
 *   <li><b>Logging</b>: Writes to timestamped files without rotation; ensure sufficient disk space for
 *       repeated runs.</li>
 * </ul>
 *
 * <p><b>Troubleshooting Notes:</b></p>
 * <ul>
 *   <li><b>Repository Corruption</b>: If traversal fails (e.g., few nodes processed), check for tar file issues (e.g., data00045a.tar corruption) using <code>oak-run check</code>.</li>
 *   <li><b>Memory Issues</b>: Monitor evictions in the log (e.g., <code>Evictions: missingPaths=X</code>). High evictions indicate many corrupted paths; increase <code>MAX_PATHS</code> or log evicted paths.</li>
 *   <li><b>Blob Access Issues</b>: Missing blobs may result from file ownership changes (e.g., AEM run as root), incorrect DataStore configuration (--fds-path, --azureblobds, --s3ds), or soft-deleted versions in S3/Azure Blob Storage. Check permissions, configuration, and soft-deleted blobs.</li>
 *   <li><b>Console Restrictions</b>: The oak-run console has a limited API surface. Avoid external dependencies or complex Groovy features.</li>
 *   <li><b>Read-Only Mode</b>: If the repository is read-only (e.g., due to tar corruption), use <code>--read-write</code> to enable full access.</li>
 * </ul>
 *
 * @see <a href="https://jackrabbit.apache.org/oak/docs/admin.html">Jackrabbit Oak Documentation</a>
 */
@CompileStatic
class CountNodesCommand extends CommandSupport {
    /** Command name for oak-run console. */
    static final String COMMAND_NAME = 'count-nodes'
    /** Interval for logging progress (nodes processed). */
    private static final int FLUSH = 50000
    /** Interval for flushing log buffer to disk (log entries). */
    private static final int LOG_FLUSH_INTERVAL = 100
    /** Threshold for warning about high child node counts. */
    private static final long WARN_AT = 1000L
    /** Maximum number of corrupted paths to store in memory to prevent memory exhaustion. */
    private static final int MAX_PATHS = 10_000
    /** File writer for logging traversal and analysis results. */
    private PrintWriter logWriter
    /** Buffer for batching log messages to reduce I/O overhead. */
    private final List<String> logBuffer

    /**
     * Constructs the command with a Groovy shell instance and initializes logging.
     *
     * <p>Creates a log file with a timestamped name (e.g., count-nodes-snfe-YYYYMMDD-HHmmss.log)
     * and sets up the log buffer for batch writing.</p>
     *
     * @param shell The Groovysh instance for the oak-run console.
     */
    CountNodesCommand(Groovysh shell) {
        super(shell, COMMAND_NAME, 'countNodes')
        // Initialize without creating log file - will be created in execute()
        logWriter = null
        logBuffer = []
        
        // Load properties for description, usage, and help
        loadProperties()
    }
    
    private Properties commandProperties = new Properties()
    
    private void loadProperties() {
        try {
            InputStream is = this.class.getResourceAsStream("CountNodesCommand.properties")
            if (is != null) {
                commandProperties.load(is)
                is.close()
            }
        } catch (Exception e) {
            // Properties will remain empty, fallback values will be used
        }
    }
    
    @Override
    String getDescription() {
        return commandProperties.getProperty("command.description", "Counts nodes and binaries in a SegmentStore repository")
    }
    
    @Override
    String getUsage() {
        return commandProperties.getProperty("command.usage", "[segment-binaries | datastore-binaries | deep] [analysis]")
    }
    
    @Override
    String getHelp() {
        return commandProperties.getProperty("command.help", "Counts nodes and binaries, detects corruption, and analyzes paths")
    }

    /**
     * Flushes the log buffer to the output file and clears it.
     *
     * <p>Writes all buffered log messages to the file in a single operation to minimize I/O.
     * Used after significant events (e.g., traversal completion, analysis) or when the buffer reaches capacity.</p>
     *
     * <p><b>Troubleshooting:</b> If the log file is empty or incomplete, check for I/O errors (e.g., disk full, permissions issues) or premature script termination.</p>
     */
    private void flushLogBuffer() {
        if (!logBuffer.isEmpty() && logWriter != null) {
            logWriter.println(logBuffer.join("\n"))
            logWriter.flush()
            logBuffer.clear()
        }
    }

    /**
     * Adds a message to the log buffer and flushes if buffer reaches capacity.
     *
     * <p>Provides periodic flushing to ensure log data is written during long-running operations.</p>
     */
    private void addToLogBuffer(String message) {
        logBuffer.add(message)
        if (logBuffer.size() >= LOG_FLUSH_INTERVAL) {
            flushLogBuffer()
        }
    }

    /**
     * Retrieves the ConsoleSession from the Groovy shell variables.
     *
     * <p>Provides access to the repository's NodeStore for traversal and analysis.</p>
     *
     * @return The ConsoleSession instance providing access to the repository.
     * @throws ClassCastException if the session variable is not a ConsoleSession.
     * @throws NullPointerException if the session variable is not set.
     */
    private ConsoleSession getSession() {
        return (ConsoleSession) variables.get("session")
    }


    /**
     * Executes the command to traverse the repository, count nodes/binaries, and analyze corruption.
     *
     * <p>Processes command-line arguments to enable deep traversal (binary stream reading) or analysis
     * (corruption path collection and heuristics). Initializes counters and collections, triggers node
     * traversal, and logs results to the console and file, including node counts, binary counts, missing
     * segments/blobs, and analysis output.</p>
     *
     * <p><b>Troubleshooting:</b></p>
     * <ul>
     *   <li><b>Few Nodes Processed</b>: Check for repository corruption (e.g., tar file issues) using <code>oak-run check</code>. Ensure <code>--read-write</code> mode if read-only.</li>
     *   <li><b>Analysis Failure</b>: Verify <code>missingPaths</code> and <code>missingPathHeuristics</code> contents in the log. Check for invalid paths (e.g., not starting with '/').</li>
     *   <li><b>High Evictions</b>: Non-zero evictions (e.g., <code>Evictions: missingPaths=X</code>) indicate many corrupted paths. Increase <code>MAX_PATHS</code> or log evicted paths.</li>
     *   <li><b>Blob False Positives</b>: Ensure <code>--fds-path</code>, <code>--azureblobds</code>, or <code>--s3ds</code> points to the correct DataStore. Check blob file ownership (e.g., <code>ls -l</code>) or soft-deleted versions in S3/Azure Blob Storage.</li>
     * </ul>
     *
     * @param args Command-line arguments (e.g., "deep", "analysis").
     * @return null, as the command outputs results to console and file.
     * @throws NullPointerException if the session or store is unavailable.
     * @throws ClassCastException if the session variable is not a ConsoleSession.
     */
    @Override
    Object execute(List<String> args) {
        io.out.println("Raw args: ${args}")
        
        // Create log file with fresh timestamp for each execution
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
        File logFile = new File("count-nodes-snfe-${timestamp}.log")
        logWriter = new PrintWriter(logFile, "UTF-8")
        logWriter.println("CountNodesCommand Log - Started at " + new Date().toString())
        
        // Validate mutually exclusive binary reading options
        boolean segmentBinaries = args.contains("segment-binaries")
        boolean datastoreBinaries = args.contains("datastore-binaries")
        boolean deep = args.contains("deep")
        boolean analysis = args.contains("analysis")
        
        int binaryModeCount = (segmentBinaries ? 1 : 0) + (datastoreBinaries ? 1 : 0) + (deep ? 1 : 0)
        if (binaryModeCount > 1) {
            io.err.println("ERROR: Binary reading options are mutually exclusive. Choose only one of: segment-binaries, datastore-binaries, deep")
            return null
        }
        
        // Determine binary reading behavior
        boolean readSegmentBinaries = segmentBinaries || deep
        boolean readDatastoreBinaries = datastoreBinaries || deep
        
        io.out.println("Received options: segment-binaries=${readSegmentBinaries}, datastore-binaries=${readDatastoreBinaries}, analysis=${analysis}")
        NodeStore store = getSession().getStore()
        NodeState root = store.getRoot()

        AtomicInteger count = new AtomicInteger(0)
        AtomicInteger binaries = new AtomicInteger(0)
        AtomicInteger missingSegments = new AtomicInteger(0)
        AtomicInteger missingBlobs = new AtomicInteger(0)
        AtomicInteger missingSegmentBlobs = new AtomicInteger(0)
        AtomicInteger missingDatastoreBlobs = new AtomicInteger(0)
        AtomicInteger missingNodeLevelBlobs = new AtomicInteger(0)
        ConcurrentHashMap<String, AtomicInteger> segmentIdCounts = new ConcurrentHashMap<>()
        BoundedSet<String> missingPaths = analysis ? new BoundedSet<>(MAX_PATHS) : null
        BoundedMap<String, List<String>> missingPathHeuristics = analysis ? new BoundedMap<>(MAX_PATHS) : null
        ConcurrentHashMap<String, Set<String>> segmentPathMap = analysis ? new ConcurrentHashMap<>() : null

        if (analysis) {
            addToLogBuffer("Initialized missingPaths: " + (missingPaths != null) + ", missingPathHeuristics: " + (missingPathHeuristics != null))
        }

        io.out.println("Counting nodes in tree /")
        addToLogBuffer("Starting node traversal at /")
        countNodes(root, "/", readSegmentBinaries, readDatastoreBinaries, count, binaries, missingSegments, missingBlobs, missingSegmentBlobs, missingDatastoreBlobs, missingNodeLevelBlobs, segmentIdCounts, analysis, missingPaths, missingPathHeuristics, segmentPathMap)
        io.out.println("Total nodes in tree /: ${count.get()}")
        io.out.println("Total binaries in tree /: ${binaries.get()}")
        io.out.println("Total missing segments: ${missingSegments.get()}")
        io.out.println("Total missing blobs: ${missingBlobs.get()}")
        if (missingBlobs.get() > 0) {
            io.out.println("  - Missing segment blobs: ${missingSegmentBlobs.get()}")
            io.out.println("  - Missing datastore blobs: ${missingDatastoreBlobs.get()}")
            if (missingNodeLevelBlobs.get() > 0) {
                io.out.println("  - Missing node-level blobs: ${missingNodeLevelBlobs.get()}")
            }
        }
        logBuffer.add("Summary: nodes=" + count.get() + ", binaries=" + binaries.get() + ", missingSegments=" + missingSegments.get() + ", missingBlobs=" + missingBlobs.get())
        logBuffer.add("Blob breakdown: segmentBlobs=" + missingSegmentBlobs.get() + ", datastoreBlobs=" + missingDatastoreBlobs.get() + ", nodeLevelBlobs=" + missingNodeLevelBlobs.get())

        if (!segmentIdCounts.isEmpty()) {
            io.out.println("Unique segment ID occurrences:")
            segmentIdCounts.each { id, counter ->
                io.out.println("Segment ID ${id}: ${counter.get()} occurrences")
                logBuffer.add("Segment ID " + id + ": " + counter.get() + " occurrences")
            }
        }
        if (analysis && missingPaths != null && !missingPaths.isEmpty()) {
            logBuffer.add("Missing paths before analysis: size=" + missingPaths.size())
            for (String path in missingPaths) {
                logBuffer.add("Path: " + path)
            }
            analyzeMissingPaths(missingPaths, missingPathHeuristics, segmentIdCounts, segmentPathMap)
        }

        logBuffer.add("Evictions: missingPaths=" + (missingPaths?.getEvictions() ?: 0) + ", missingPathHeuristics=" + (missingPathHeuristics?.getEvictions() ?: 0))
        flushLogBuffer()
        if (logWriter != null) {
            logWriter.println("Execution completed at " + new Date().toString())
            logWriter.close()
        }
        return null
    }

    /**
     * Recursively traverses the JCR node tree, counting nodes and binaries, and detecting corruption.
     *
     * <p>Processes a node and its properties, checking for binary properties and attempting to read them
     * (if deep mode is enabled). Detects SegmentNotFoundException (SNFE) or missing blobs, logging errors
     * and collecting corrupted paths for analysis. Recurses into child nodes to continue traversal.</p>
     *
     * <p><b>Troubleshooting:</b></p>
     * <ul>
     *   <li><b>Early Traversal Stop</b>: Check for exceptions in the log (e.g., SNFE, <code>DataStoreException</code>). Verify repository state with <code>oak-run check</code>.</li>
     *   <li><b>No Blobs Detected</b>: Ensure appropriate binary reading mode is enabled (<code>segment-binaries</code>, <code>datastore-binaries</code>, or <code>deep</code>). Check <code>--fds-path</code>, <code>--azureblobds</code>, or <code>--s3ds</code> and blob file permissions (e.g., <code>ls -l</code>).</li>
     *   <li><b>High Child Node Counts</b>: Warnings for nodes with >1000 children (e.g., <code>/oak:index/uuid/:index/</code>) may indicate performance bottlenecks; consider indexing optimizations.</li>
     *   <li><b>Path Collection Issues</b>: If paths are not added to <code>missingPaths</code>, check validation (<code>path.startsWith("/")</code>) and evictions in the log.</li>
     * </ul>
     *
     * @param n The current node state to process.
     * @param path The JCR path of the current node.
     * @param readSegmentBinaries If true, reads internal segment blob streams (small, fast).
     * @param readDatastoreBinaries If true, reads external DataStore blob streams (large, slow).
     * @param count Counter for total nodes processed.
     * @param binaries Counter for binary properties encountered.
     * @param missingSegments Counter for SegmentNotFoundException occurrences.
     * @param missingBlobs Counter for missing blob occurrences (total).
     * @param missingSegmentBlobs Counter for missing segment blob occurrences.
     * @param missingDatastoreBlobs Counter for missing DataStore blob occurrences.
     * @param missingNodeLevelBlobs Counter for missing node-level blob occurrences.
     * @param segmentIdCounts Map of segment IDs to their occurrence counts.
     * @param analysis If true, collects corrupted paths for heuristic analysis.
     * @param missingPaths Bounded set of corrupted paths (SNFE or missing blobs).
     * @param missingPathHeuristics Bounded map of paths to their heuristic data (type, message, heuristicType).
     * @param segmentPathMap Map of segment IDs to affected paths for multi-path analysis.
     */
    private void countNodes(NodeState n,
                            String path,
                            boolean readSegmentBinaries,
                            boolean readDatastoreBinaries,
                            AtomicInteger count,
                            AtomicInteger binaries,
                            AtomicInteger missingSegments,
                            AtomicInteger missingBlobs,
                            AtomicInteger missingSegmentBlobs,
                            AtomicInteger missingDatastoreBlobs,
                            AtomicInteger missingNodeLevelBlobs,
                            ConcurrentHashMap<String, AtomicInteger> segmentIdCounts,
                            boolean analysis,
                            BoundedSet<String> missingPaths,
                            BoundedMap<String, List<String>> missingPathHeuristics,
                            ConcurrentHashMap<String, Set<String>> segmentPathMap) {
        int cnt = count.incrementAndGet()
        if (cnt % FLUSH == 0) {
            io.out.println("  " + cnt)
            addToLogBuffer("Progress: " + cnt + " nodes processed")
        }

        try {
            for (PropertyState prop : (Iterable<PropertyState>) n.getProperties()) {
                if (prop.getType() == Type.BINARY || prop.getType() == Type.BINARIES) {
                    try {
                        for (def b : prop.getValue(Type.BINARIES)) {
                            binaries.incrementAndGet()
                            
                            // Determine blob type and whether to read stream
                            // Note: Even isExternal() can trigger DataStore access for corrupted blobs
                            boolean isExternal = false
                            boolean isExternalCheckFailed = false
                            
                            // Check if this is a DataStore blob by class type first
                            String blobClassName = b.getClass().getSimpleName()
                            if (blobClassName.contains("BlobStoreBlob") || blobClassName.contains("DataStore")) {
                                // This is definitely a DataStore blob
                                isExternal = true
                            } else if (b instanceof SegmentBlob) {
                                try {
                                    isExternal = ((SegmentBlob) b).isExternal()
                                } catch (Exception e) {
                                    // isExternal() failed - likely a DataStore blob with missing record
                                    isExternalCheckFailed = true
                                    isExternal = true  // Assume external since the check failed
                                }
                            } else {
                                // Unknown blob type - assume external to be safe
                                isExternal = true
                            }
                            
                            boolean shouldReadStream = false
                            if (isExternal && readDatastoreBinaries) {
                                shouldReadStream = true  // DataStore blob
                            } else if (!isExternal && readSegmentBinaries) {
                                shouldReadStream = true  // Segment blob
                            }
                            
                            if (isExternalCheckFailed) {
                                // isExternal() already failed - this blob is corrupted, skip all operations
                                // We'll catch this in the outer catch block
                                throw new RuntimeException("Blob corrupted during isExternal() check")
                            } else if (shouldReadStream) {
                                // Read the entire blob stream like ConsistencyChecker
                                InputStream s = b.getNewStream()
                                try {
                                    byte[] buffer = new byte[8192]  // Use 8KB buffer like ConsistencyChecker
                                    int bytesRead = s.read(buffer, 0, buffer.length)
                                    while (bytesRead >= 0) {
                                        bytesRead = s.read(buffer, 0, buffer.length)
                                    }
                                } finally { s.close() }
                            } else if (!isExternal) {
                                // Only check blob metadata for segment blobs (internal blobs)
                                // Skip ALL operations for external blobs when not reading DataStore binaries
                                b.length()
                            }
                            // If isExternal=true and !shouldReadStream, skip all blob operations
                        }
                    } catch (Exception e) {
                        missingBlobs.incrementAndGet()
                        
                        // Determine blob type for logging and counting
                        boolean isExternal = false
                        String blobTypeLabel = "unknown"
                        // Note: 'b' variable is not accessible here due to scope, 
                        // so we'll classify based on exception type instead
                        String exceptionMsg = getExceptionMessage(e)
                        if (exceptionMsg.contains("DataStore") || (exceptionMsg.contains("Record") && exceptionMsg.contains("does not exist"))) {
                            isExternal = true
                            blobTypeLabel = "datastore"
                            missingDatastoreBlobs.incrementAndGet()
                        } else if (exceptionMsg.contains("Segment") && exceptionMsg.contains("not found")) {
                            isExternal = false
                            blobTypeLabel = "segment"
                            missingSegmentBlobs.incrementAndGet()
                        } else {
                            // Default to segment blob for unknown exceptions at property level
                            isExternal = false
                            blobTypeLabel = "segment"
                            missingSegmentBlobs.incrementAndGet()
                        }
                        
                        // Log with blob type information for clarity, but maintain remove-nodes compatibility
                        String baseMsg = "Warning: Missing blob at " + path + ": " + getExceptionMessage(e)
                        String enhancedMsg = "Warning: Missing blob (" + blobTypeLabel + ") at " + path + ": " + getExceptionMessage(e)
                        
                        io.out.println(enhancedMsg)  // Console shows enhanced format
                        addToLogBuffer(baseMsg)       // Log file uses remove-nodes compatible format
                        addToLogBuffer("  -> Blob type: " + blobTypeLabel + " (external=" + isExternal + ")")  // Additional detail line
                        
                        if (analysis && missingPaths != null && missingPathHeuristics != null && path.startsWith("/")) {
                            logBuffer.add("Attempting to add blob path: " + path)
                            if (missingPaths.add(path)) {
                                def heuristic = applyHeuristic(path, false, null, segmentPathMap)
                                missingPathHeuristics.put(path, ["blob", heuristic.message, heuristic.type])
                                logBuffer.add("Added blob path " + path + " to heuristics")
                            } else {
                                logBuffer.add("Failed to add blob path " + path + "; rejected by BoundedSet")
                            }
                        } else {
                            logBuffer.add("Skipped blob path " + path + " for analysis; analysis=" + analysis + ", missingPaths=" + (missingPaths != null) + ", missingPathHeuristics=" + (missingPathHeuristics != null) + ", validPath=" + path.startsWith("/"))
                        }
                    }
                }
            }

            long kids = n.getChildNodeCount(WARN_AT)
            if (kids >= WARN_AT) {
                io.out.println(path + " has " + kids + " child nodes")
                logBuffer.add(path + " has " + kids + " child nodes")
            }

            for (ChildNodeEntry child : (Iterable<ChildNodeEntry>) n.getChildNodeEntries()) {
                NodeState childState = child.getNodeState()
                countNodes(childState, path + child.getName() + "/", readSegmentBinaries, readDatastoreBinaries, count, binaries, missingSegments, missingBlobs, missingSegmentBlobs, missingDatastoreBlobs, missingNodeLevelBlobs, segmentIdCounts, analysis, missingPaths, missingPathHeuristics, segmentPathMap)
            }
        } catch (Exception e) {
            String message = getExceptionMessage(e)
            boolean segmentNotFound = message.contains("Segment") && message.contains("not found")
            boolean blobNotFound = message.contains("Record") && message.contains("does not exist")
            if (segmentNotFound) {
                missingSegments.incrementAndGet()
                String segmentId = extractSegmentId(message)
                segmentIdCounts.computeIfAbsent(segmentId, { new AtomicInteger(0) }).incrementAndGet()
                String msg = "Warning: Missing segment at " + path + ": " + message
                io.out.println(msg)
                addToLogBuffer(msg)
                if (analysis && missingPaths != null && missingPathHeuristics != null && path.startsWith("/")) {
                    logBuffer.add("Attempting to add segment path: " + path)
                    if (missingPaths.add(path)) {
                        segmentPathMap?.computeIfAbsent(segmentId, { new HashSet<>() })?.add(path)
                        def heuristic = applyHeuristic(path, true, segmentId, segmentPathMap)
                        missingPathHeuristics.put(path, ["segment", heuristic.message, heuristic.type])
                        logBuffer.add("Added segment path " + path + " to heuristics")
                    } else {
                        logBuffer.add("Failed to add segment path " + path + "; rejected by BoundedSet")
                    }
                } else {
                    logBuffer.add("Skipped segment path " + path + " for analysis; analysis=" + analysis + ", missingPaths=" + (missingPaths != null) + ", missingPathHeuristics=" + (missingPathHeuristics != null) + ", validPath=" + path.startsWith("/"))
                }
            } else if (blobNotFound) {
                missingBlobs.incrementAndGet()
                
                // DataStore record missing during node traversal - this is a DataStore blob issue
                // caught at node level, not a true "node-level" blob issue
                missingDatastoreBlobs.incrementAndGet()
                
                String baseMsg = "Warning: Missing blob at " + path + ": " + message
                String enhancedMsg = "Warning: Missing blob (datastore-node-level) at " + path + ": " + message
                
                io.out.println(enhancedMsg)  // Console shows enhanced format
                addToLogBuffer(baseMsg)       // Log file uses remove-nodes compatible format
                addToLogBuffer("  -> Blob type: datastore-node-level (DataStore record missing during node traversal)")  // Additional detail line
                
                if (analysis && missingPaths != null && missingPathHeuristics != null && path.startsWith("/")) {
                    logBuffer.add("Attempting to add blob path: " + path)
                    if (missingPaths.add(path)) {
                        def heuristic = applyHeuristic(path, false, null, segmentPathMap)
                        missingPathHeuristics.put(path, ["blob", heuristic.message, heuristic.type])
                        logBuffer.add("Added blob path " + path + " to heuristics")
                    } else {
                        logBuffer.add("Failed to add blob path " + path + "; rejected by BoundedSet")
                    }
                } else {
                    logBuffer.add("Skipped blob path " + path + " for analysis; analysis=" + analysis + ", missingPaths=" + (missingPaths != null) + ", missingPathHeuristics=" + (missingPathHeuristics != null) + ", validPath=" + path.startsWith("/"))
                }
            } else {
                // Check if it's a true blob-related node traversal issue (rare)
                boolean isBlobRelated = message.toLowerCase().contains("blob") || 
                                       message.toLowerCase().contains("binary") ||
                                       message.toLowerCase().contains("stream")
                
                if (isBlobRelated) {
                    // True node-level blob issue - blob corruption affecting node traversal
                    missingBlobs.incrementAndGet()
                    missingNodeLevelBlobs.incrementAndGet()
                    
                    String baseMsg = "Warning: Missing blob at " + path + ": " + message
                    String enhancedMsg = "Warning: Missing blob (node-level) at " + path + ": " + message
                    
                    io.out.println(enhancedMsg)
                    addToLogBuffer(baseMsg)
                    addToLogBuffer("  -> Blob type: node-level (blob corruption affecting node structure)")
                    
                    if (analysis && missingPaths != null && missingPathHeuristics != null && path.startsWith("/")) {
                        logBuffer.add("Attempting to add blob path: " + path)
                        if (missingPaths.add(path)) {
                            def heuristic = applyHeuristic(path, false, null, segmentPathMap)
                            missingPathHeuristics.put(path, ["blob", heuristic.message, heuristic.type])
                            logBuffer.add("Added blob path " + path + " to heuristics")
                        } else {
                            logBuffer.add("Failed to add blob path " + path + "; rejected by BoundedSet")
                        }
                    } else {
                        logBuffer.add("Skipped blob path " + path + " for analysis; analysis=" + analysis + ", missingPaths=" + (missingPaths != null) + ", missingPathHeuristics=" + (missingPathHeuristics != null) + ", validPath=" + path.startsWith("/"))
                    }
                } else {
                    // General node read error (not blob-related)
                    String msg = "Warning: Unable to read node " + path + ": " + message
                    io.out.println(msg)
                    addToLogBuffer(msg)
                }
            }
        }
    }

    /**
     * Extracts the segment ID from a SegmentNotFoundException message.
     *
     * <p>Parses the exception message to retrieve the segment ID for tracking and analysis.</p>
     *
     * <p><b>Troubleshooting:</b> If segment IDs are not extracted, verify the exception message format (e.g., "Segment <ID> not found").</p>
     *
     * @param message The exception message containing the segment ID.
     * @return The extracted segment ID, or the original message if not found.
     */
    private String extractSegmentId(String message) {
        def matcher = message =~ /Segment (\S+) not found/
        return matcher.find() ? matcher.group(1) : message
    }

    /**
     * Retrieves the root cause message from an exception chain.
     *
     * <p>Traverses the exception cause chain to find the most specific error message.</p>
     *
     * <p><b>Troubleshooting:</b> If "Unknown error" is returned, the exception lacks a message. Log the full stack trace for debugging.</p>
     *
     * @param e The exception to analyze.
     * @return The root cause message, or "Unknown error" if none found.
     */
    private String getExceptionMessage(Exception e) {
        Throwable current = e
        while (current != null) {
            if (current.message != null) { return current.message }
            current = current.cause
        }
        return "Unknown error"
    }

    /**
     * Checks if a path is under a specific node name (e.g., ":data").
     *
     * <p>Used to identify paths within Lucene index data or suggest-data nodes for heuristic analysis.</p>
     *
     * @param path The JCR path to check.
     * @param nodeName The node name to search for (e.g., ":data").
     * @return True if the path contains the node name, false otherwise.
     */
    private boolean isBlobUnder(String path, String nodeName) {
        return path.contains("/" + nodeName + "/")
    }

    /**
     * Extracts the index definition node from an oak:index path.
     *
     * <p>Used to identify the parent index node (e.g., /oak:index/lucene) for reindexing recommendations.</p>
     *
     * @param path The JCR path to analyze.
     * @return The index definition path (e.g., /oak:index/lucene), or /oak:index if not found.
     */
    private String getIndexDefNode(String path) {
        def matcher = path =~ /^(\/oak:index\/[^\/]+)/
        return matcher.find() ? matcher.group(1) : "/oak:index"
    }

    /**
     * Identifies the parent segment (e.g., :data, :suggest-data) in a path.
     *
     * <p>Used to pinpoint the specific index segment affected by corruption.</p>
     *
     * @param path The JCR path to analyze.
     * @return The parent segment name (e.g., :data), or "(unknown)" if not found.
     */
    private String getParentSegment(String path) {
        def matcher = path =~ /:(data|suggest-data)/
        return matcher.find() ? matcher.group(0) : "(unknown)"
    }

    /**
     * Extracts the index data path for Lucene cleanup operations.
     *
     * <p>Provides the path for JMX cleanup commands (e.g., /oak:index/lucene/:data/).</p>
     *
     * @param path The JCR path to analyze.
     * @return The index data path, or a placeholder if not found.
     */
    private String getIndexDataPath(String path) {
        def matcher = path =~ /(\/oak:index\/[^\/]+\/:(data|suggest-data)\/)/
        return matcher.find() ? matcher.group(1) : "<provide hidden :data node path>"
    }

    /**
     * Checks if a path is part of a full-text index (e.g., damAssetLucene, lucene).
     *
     * <p>Used to provide specific reindexing guidance for full-text indexes.</p>
     *
     * @param path The JCR path to analyze.
     * @return True if the path is a full-text index, false otherwise.
     */
    private boolean isFulltextIndex(String path) {
        return path.startsWith("/oak:index/damAssetLucene") || path.startsWith("/oak:index/lucene")
    }

    /**
     * Applies AEM-specific heuristics to suggest recovery actions for corrupted paths.
     *
     * <p>Generates recovery recommendations for SegmentNotFoundException (SNFE) or missing blobs,
     * based on the path and corruption type. For SNFE, recommends deletion as a last resort (Plan B).
     * For blobs, provides mutually exclusive options: restore from backup (preferred) or delete the
     * reference (fallback). Returns a heuristic type to categorize the recommendation for analysis.</p>
     *
     * <p><b>Troubleshooting:</b></p>
     * <ul>
     *   <li><b>Missing Heuristics</b>: Check <code>missingPathHeuristics</code> additions in <code>countNodes</code>. Verify path validation (<code>path.startsWith("/")</code>).</li>
     *   <li><b>Incorrect Recommendations</b>: Ensure path patterns match expected AEM paths (e.g., /oak:index, /content/dam). Log <code>path</code> values for debugging.</li>
     *   <li><b>JMX Cleanup Issues</b>: For Lucene index blobs, ensure the <code>performPropertyIndexCleanup</code> operation is invoked via the Lucene Index Statistics MBean at <code>/system/console/jmx</code>. Use recommended parameters (e.g., batchSize=100, sleepPerBatch=0, maxRemoveCount=1000). Check for timeouts or permission errors in AEM logs.</li>
     *   <li><b>Multi-Path SNFE</b>: Ensure <code>segmentPathMap</code> is populated for accurate multi-path detection. Check segment ID counts in the log.</li>
     * </ul>
     *
     * @param path The corrupted JCR path.
     * @param isSegment True if the corruption is an SNFE, false for a missing blob.
     * @param segmentId The segment ID for SNFE (null for blobs).
     * @param segmentPathMap Map of segment IDs to affected paths for multi-path analysis.
     * @return A map with severity (e.g., CRITICAL, HIGH, INFO), recovery message, and heuristic type.
     */
    private Map<String, String> applyHeuristic(String path, boolean isSegment, String segmentId, ConcurrentHashMap<String, Set<String>> segmentPathMap) {
        String severity = isSegment ? "CRITICAL" : "INFO"
        String comment = isSegment ? "Remove node at '${path}' (DATA LOSS EXPECTED)." : "Restore from backup or cleanup."
        String heuristicType = isSegment ? "segment" : "genericBlob"

        if (isSegment) {
            Set<String> affectedPaths = segmentPathMap?.get(segmentId) ?: [path] as Set
            if (affectedPaths.size() > 1) {
                severity = "ULTRA-CRITICAL"
                comment = "Multi-path segment (ID: ${segmentId}) at '${path}'. Affected: ${affectedPaths.size()} paths. Delete ALL affected paths: ${affectedPaths.join(", ")}. Check tar-compaction (02:00–04:00) and remove nodes."
                heuristicType = "multiPathSegment"
            }
            if (path.startsWith("/oak:index")) {
                comment = "CRITICAL: Index corruption due to missing segment. Remove the entire index definition node at '${getIndexDefNode(path)}', recreate it, and trigger FULL reindex. Indexes affected by missing segments cannot generally be recovered by other means."
                heuristicType = "indexSegment"
            } else if (path.startsWith("/:async")) {
                comment = "CRITICAL: The :async node is corrupted; recovery extremely difficult. Likely needs sidegrade or professional support."
                heuristicType = "asyncSegment"
            } else if (path.startsWith("/oak:index/uuid") || path.startsWith("/oak:index/reference")) {
                comment = "CRITICAL: Corruption affects property index. Recommend oak-upgrade sidegrade or expert/manual recovery."
                heuristicType = "propertyIndexSegment"
            } else if (path.startsWith("/libs")) {
                comment = "WARNING: Loss in /libs. Replace via content package from clean install of same AEM version."
                heuristicType = "libsSegment"
            } else if (path.startsWith("/apps")) {
                comment = "WARNING: Loss in /apps. Try to restore from package or backup."
                heuristicType = "appsSegment"
            } else if (path.startsWith("/content") && !path.startsWith("/content/dam")) {
                comment = "INFO: User content affected. Restore from backup, or replace as appropriate."
                heuristicType = "contentSegment"
            } else if (path.startsWith("/content/dam")) {
                comment = "INFO: DAM asset affected. Restore from DAM backup or re-upload assets."
                heuristicType = "damSegment"
            } else if (path.startsWith("/var/workflow")) {
                comment = "INFO: Workflow state corruption at '${path}'. Usually from crash or disk space; not a core content issue. Delete transient node or restore from backup."
                heuristicType = "workflowSegment"
            } else if (path.startsWith("/var/audit")) {
                comment = "INFO: Audit log corruption at '${path}'. Often caused by out-of-disk or abrupt AEM stop. Delete transient node or restore from backup."
                heuristicType = "auditSegment"
            } else if (path.startsWith("/var/eventing")) {
                comment = "INFO: Eventing/Sling job corruption at '${path}'. Typically results from abrupt system stop/disk issues. Delete transient node or restore from backup."
                heuristicType = "eventingSegment"
            } else if (path.startsWith("/var/replication")) {
                comment = "INFO: Replication event data corruption at '${path}'. Typically due to replication queue or history item loss from crash, full disk, or forceful shutdown. Recovery: Clear or reinitialize the queue via agent management or repository cleanup. Check I/O health and AEM logs."
                heuristicType = "replicationSegment"
            } else if (path.startsWith("/etc/packages")) {
                comment = "INFO: Package data corruption at '${path}'. Likely from incomplete package install or out-of-disk event. Review recent system/package management logs and restore from backup."
                heuristicType = "packagesSegment"
            } else {
                comment = "INFO: Unclassified path corruption at '${path}'. Analyze in context or consult support."
                heuristicType = "unclassifiedSegment"
            }
            if (!path.startsWith("/oak:index")) {
                comment += " [Segment missing detected]"
            }
        } else {
            if (path.startsWith("/oak:index") && (isBlobUnder(path, ":data") || isBlobUnder(path, ":suggest-data"))) {
                severity = "HIGH"
                comment = """
Lucene index data corruption under '${getParentSegment(path)}'.
Options (mutually exclusive):
  1) Preferred: Restore the blob from a backup (FileDataStore, S3, Azure Blob Storage) if feasible.
  2) Fallback: Run JMX cleanup to remove the reference if restoration is not possible:
     - Suspend ALL async indexers by invoking abortAndPause() via:
        - /system/console/jmx/org.apache.jackrabbit.oak:name=async,type=IndexStats
        - /system/console/jmx/org.apache.jackrabbit.oak:name=fulltext-async,type=IndexStats
     - On the Lucene Index Statistics MBean, invoke:
        performPropertyIndexCleanup(paths='${getIndexDataPath(path)}', batchSize=100, sleepPerBatch=0, maxRemoveCount=1000)
     - Resume indexers and trigger FULL reindex.
""" + (isFulltextIndex(path) ? """
IMPORTANT: Full-text index detected (e.g., damAssetLucene, lucene). Pre-extract full text to avoid I/O spikes during reindexing:
  See: https://git.corp.adobe.com/pages/custsat/aem-troubleshooting-guide/aem-content-problem-help-guides/howto-reindex-with-pretextextraction.html
""" : "") +
"""
See: https://experienceleague.adobe.com/docs/experience-manager-65/administering/operations/index-tools.html
"""
                heuristicType = "luceneIndex"
            } else if (path.startsWith("/oak:index")) {
                severity = "HIGH"
                comment = """
Index corruption due to missing blob at '${path}'.
Options (mutually exclusive):
  1) Preferred: Restore the blob from a backup (FileDataStore, S3, Azure Blob Storage) if feasible.
  2) Fallback: Try JMX Lucene Index MBean cleanup, then reindex. If unrecoverable, remove and recreate the index at '${getIndexDefNode(path)}'.
"""
                heuristicType = "indexBlob"
            } else if (path.startsWith("/content/dam")) {
                if (path.contains("/renditions/") && !path.endsWith("/renditions/original") && !path.endsWith("/renditions/original/jcr:content/")) {
                    // Rendition case
                    def renditionNode = path.replaceFirst(/\/jcr:content\/$/, "")
                    def assetNode = path.replaceFirst(/\/jcr:content\/renditions\/[^\/]+\/jcr:content\/$/, "")
                    comment = """
DAM asset rendition affected at '${path}'.
Options (mutually exclusive):
  1) Preferred: Restore the rendition blob from a backup (FileDataStore, S3, Azure Blob Storage) if feasible.
  2) Fallback: Delete the rendition node at '${renditionNode}' if restoration is not possible, then regenerate the rendition from the original binary at '${assetNode}/jcr:content/renditions/original'. Ensure the original binary exists before regeneration.
"""
                    heuristicType = "damRendition"
                } else if (path.endsWith("/renditions/original") || path.endsWith("/renditions/original/jcr:content/")) {
                    // Original binary case
                    def assetNode = path.replaceFirst(/\/jcr:content\/renditions\/original(\/jcr:content\/)?$/, "")
                    comment = """
DAM asset original binary affected at '${path}'.
Options (mutually exclusive):
  1) Preferred: Restore the original binary blob from a backup (FileDataStore, S3, Azure Blob Storage) if feasible.
  2) Fallback: Delete the entire dam:Asset node at '${assetNode}' if restoration is not possible, as an asset without its original binary is incomplete. Consider re-uploading the asset or restoring from backup.
"""
                    heuristicType = "damOriginal"
                } else {
                    // Generic DAM case (fallback for unexpected paths)
                    comment = """
DAM asset affected at '${path}'.
Options (mutually exclusive):
  1) Preferred: Restore the blob from a backup (FileDataStore, S3, Azure Blob Storage) if feasible.
  2) Fallback: Delete the asset reference at '${path}' if restoration is not possible; if original binary, restore from backup; if rendition, regenerate.
"""
                    heuristicType = "damGeneric"
                }
            } else if (path.startsWith("/content") && !path.startsWith("/content/dam")) {
                comment = """
User content affected at '${path}'.
Options (mutually exclusive):
  1) Preferred: Restore the blob from a backup (FileDataStore, S3, Azure Blob Storage) if feasible.
  2) Fallback: Delete the reference at '${path}' if restoration is not possible; restore from backup if critical content.
"""
                heuristicType = "contentBlob"
            } else if (path.startsWith("/apps")) {
                comment = """
Application code affected at '${path}'.
Options (mutually exclusive):
  1) Preferred: Restore the blob from a backup (FileDataStore, S3, Azure Blob Storage) if feasible.
  2) Fallback: Delete the reference at '${path}' if restoration is not possible; redeploy artifacts from source control or content package.
"""
                heuristicType = "appsBlob"
            } else if (path.startsWith("/libs")) {
                comment = """
AEM library content affected at '${path}'.
Options (mutually exclusive):
  1) Preferred: Restore the blob from a backup (FileDataStore, S3, Azure Blob Storage) if feasible.
  2) Fallback: Delete the reference at '${path}' if restoration is not possible; replace with content package from a clean AEM instance of the same version.
"""
                heuristicType = "libsBlob"
            } else if (path.startsWith("/var")) {
                comment = """
Transient data affected at '${path}' (e.g., workflows, replication queues).
Options (mutually exclusive):
  1) Preferred: Restore the blob from a backup (FileDataStore, S3, Azure Blob Storage) if feasible.
  2) Fallback: Delete the reference at '${path}' if restoration is not possible; typically transient, verify system stability.
"""
                heuristicType = "varBlob"
            } else if (path.startsWith("/jcr:system/jcr:versionStorage")) {
                comment = """
Version history affected at '${path}'.
Options (mutually exclusive):
  1) Preferred: Restore the blob from a backup (FileDataStore, S3, Azure Blob Storage) if feasible.
  2) Fallback: Delete the version reference at '${path}' if restoration is not possible; preserve unaffected history.
"""
                heuristicType = "versionStorage"
            } else if (path.startsWith("/etc/packages")) {
                comment = """
Package data affected at '${path}'.
Options (mutually exclusive):
  1) Preferred: Restore the blob from a backup (FileDataStore, S3, Azure Blob Storage) if feasible.
  2) Fallback: Delete the reference at '${path}' if restoration is not possible; review package management logs and reinstall affected packages.
"""
                heuristicType = "packagesBlob"
            } else {
                comment = """
Content affected at '${path}'.
Options (mutually exclusive):
  1) Preferred: Restore the blob from a backup (FileDataStore, S3, Azure Blob Storage) if feasible.
  2) Fallback: Delete the reference at '${path}' if restoration is not possible; analyze in context or consult support.
"""
                heuristicType = "unclassifiedBlob"
            }
        }

        return [severity: severity, message: comment, type: heuristicType]
    }

    /**
     * Analyzes corrupted paths and generates a detailed report with recovery recommendations.
     *
     * <p>Groups corrupted paths by type (SNFE or blob) and heuristic type, logging one example path per heuristic type to the
     * console and file. Includes segment IDs, affected paths, branch-level corruption counts, and
     * DataStore diagnostics (logged once if blobs are detected). Adds visual separators between example paths and between diagnostics and analysis for improved readability.</p>
     *
     * <p><b>Troubleshooting:</b></p>
     * <ul>
     *   <li><b>Empty Analysis</b>: Check <code>missingPaths</code> contents in the log. Ensure paths were added in <code>countNodes</code>.</li>
     *   <li><b>Missing Heuristics</b>: Verify <code>missingPathHeuristics</code> additions in <code>countNodes</code>. Check for evictions or invalid paths.</li>
     *   <li><b>High Evictions</b>: Non-zero evictions indicate many corrupted paths. Increase <code>MAX_PATHS</code> or log evicted paths for manual review.</li>
     *   <li><b>Invalid Paths</b>: Paths not starting with '/' are skipped. Check <code>countNodes</code> for path validation issues.</li>
     *   <li><b>Blob False Positives</b>: Verify DataStore configuration and check for soft-deleted versions, as noted in the DataStore Diagnostics section.</li>
     * </ul>
     *
     * @param paths Bounded set of corrupted paths.
     * @param heuristics Bounded map of paths to their heuristic data (type, message, heuristicType).
     * @param segmentIdCounts Map of segment IDs to their occurrence counts.
     * @param segmentPathMap Map of segment IDs to affected paths.
     */
    private void analyzeMissingPaths(BoundedSet<String> paths, BoundedMap<String, List<String>> heuristics, ConcurrentHashMap<String, AtomicInteger> segmentIdCounts, ConcurrentHashMap<String, Set<String>> segmentPathMap) {
        logBuffer.add("=== ANALYSIS OF CORRUPTED PATHS (" + new Date().toString() + ") ===")
        io.out.println("\n=== ANALYSIS OF CORRUPTED PATHS ===")

        Map<String, List<Map<String, String>>> blobs = [:].withDefault { [] }
        Map<String, List<String>> segments = [:].withDefault { [] }

        paths.each { String path ->
            if (!path.startsWith("/")) {
                String msg = "Warning: Invalid path " + path + " skipped; expected JCR path starting with '/'"
                io.out.println(msg)
                logBuffer.add(msg)
                return
            }
            def heuristic = heuristics.get(path)
            def type = heuristic?.get(0)
            def reason = heuristic?.get(1)
            def heuristicType = heuristic?.get(2)
            if (type == "segment") {
                segments[heuristicType].add(path)
            } else if (type == "blob") {
                blobs[heuristicType].add([path: path, reason: reason])
            }
        }

        if (segments.size() > 0) {
            io.out.println("\nCorruptions Due To MISSING SEGMENTS (removal recommended):")
            logBuffer.add("\nCorruptions Due To MISSING SEGMENTS (removal recommended):")
            segmentPathMap?.each { segmentId, pathList ->
                String examplePath = pathList[0]
                int count = pathList.size()
                io.out.println(sprintf(
                    "  Segment ID: %-20s Occurrences: %d\n  Example Path: %-70s\n  Affected Paths: %s\n  Recovery: Follow the steps in the Reason section.\n",
                    segmentId, segmentIdCounts[segmentId]?.get() ?: 0, examplePath, pathList.join(", ")
                ))
                logBuffer.add(sprintf(
                    "  Segment ID: %-20s Occurrences: %d\n  Example Path: %-70s\n  Affected Paths: %s\n  Recovery: Follow the steps in the Reason section.\n",
                    segmentId, segmentIdCounts[segmentId]?.get() ?: 0, examplePath, pathList.join(", ")
                ))
                pathList.each { path ->
                    def heuristic = heuristics.get(path)
                    def reason = heuristic?.get(1)
                    io.out.println("  Path: " + path + "\n  Reason: " + reason + "\n")
                    logBuffer.add("  Path: " + path + "\n  Reason: " + reason + "\n")
                }
            }
        }

        if (blobs.size() > 0) {
            io.out.println("\nCorruptions Due To MISSING BLOBS (recover blob or remove reference):")
            logBuffer.add("\nCorruptions Due To MISSING BLOBS (recover blob or remove reference):")
            io.out.println("\nDataStore Diagnostics:")
            logBuffer.add("\nDataStore Diagnostics:")
            String diagnosticComment = """
  - Check DataStore file ownership for FileDataStore (e.g., ls -l /path/to/datastore). Run chown -R aemuser:aemuser /path/to/datastore if AEM was run as a non-standard user (e.g., root).
  - Verify oak-run command includes correct --fds-path, --azureblobds, or --s3ds for the DataStore. Incorrect configuration causes false positives for missing blobs.
  - For Amazon S3 (--s3ds), check if versioning is enabled on the bucket. Deleted blobs may be recoverable as previous versions; list versions via AWS Console or 'aws s3api list-object-versions' and restore using 'aws s3api copy-object' to the current version.
  - For Azure Blob Storage (--azureblobds), check if soft delete and versioning are enabled. Soft-deleted blobs or versions may appear missing; list them via Azure Portal ('Show deleted blobs') or 'az storage blob list --include d' and restore using 'Undelete Blob' or promote a previous version.
"""
            io.out.println(diagnosticComment)
            logBuffer.add(diagnosticComment)
            io.out.println("========")
            logBuffer.add("========")
            blobs.each { String heuristicType, List<Map<String, String>> pathList ->
                Map<String, String> example = pathList[0]
                String examplePath = example.path
                String reason = example.reason
                int count = pathList.size()
                io.out.println(sprintf(
                    "  Example Path: %-70s\n  Reason: %s\n  Affected Paths Count: %d\n  Recovery: Follow the steps in the Reason section.\n----\n",
                    examplePath, reason, count
                ))
                logBuffer.add(sprintf(
                    "  Example Path: %-70s\n  Reason: %s\n  Affected Paths Count: %d\n  Recovery: Follow the steps in the Reason section.\n----\n",
                    examplePath, reason, count
                ))
            }
        }

        Map<String, Integer> prefixCounts = [:].withDefault { 0 }
        paths.each { String path ->
            def prefix = path.split("/")[1]
            if (prefix) { prefixCounts[prefix] += 1 }
        }
        io.out.println("\nCorruptions grouped by repository branch:")
        logBuffer.add("\nCorruptions grouped by repository branch:")
        prefixCounts.sort { -it.value }.each { prefix, count ->
            io.out.println("  /" + prefix + " : " + count + " corruptions")
            logBuffer.add("  /" + prefix + " : " + count + " corruptions")
        }

        flushLogBuffer()
    }

    /**
     * A bounded set implementation to store unique items with a size limit.
     *
     * <p>Used to store corrupted paths (SNFE or missing blobs), evicting the oldest entry when the limit
     * (<code>maxSize</code>) is reached. Tracks evictions to monitor data loss due to capacity constraints.</p>
     *
     * <p><b>Troubleshooting:</b></p>
     * <ul>
     *   <li><b>High Evictions</b>: Non-zero <code>evictions</code> (logged as <code>Evictions: missingPaths=X</code>) indicate many corrupted paths. Increase <code>maxSize</code> or log evicted paths for manual review.</li>
     *   <li><b>Invalid Items</b>: Non-string or non-JCR paths (not starting with '/') are rejected. Check <code>add</code> calls in <code>countNodes</code>.</li>
     * </ul>
     */
    static class BoundedSet<T> {
        private final LinkedHashSet<T> set
        private final int maxSize
        private int evictions = 0

        BoundedSet(int maxSize) {
            this.set = new LinkedHashSet<>()
            this.maxSize = maxSize
        }

        boolean add(T item) {
            if (!(item instanceof String) || !((String) item).startsWith("/")) {
                return false
            }
            if (set.size() >= maxSize) {
                set.remove(set.iterator().next())
                evictions++
            }
            return set.add(item)
        }

        boolean isEmpty() { set.isEmpty() }
        int size() { set.size() }
        Iterator<T> iterator() { set.iterator() }
        int getEvictions() { evictions }
        String toString() { set.toString() }
    }

    /**
     * A bounded map implementation to store key-value pairs with a size limit.
     *
     * <p>Used to store corrupted paths and their heuristics (type, message, heuristicType), evicting the oldest entry
     * when the limit (<code>maxSize</code>) is reached. Tracks evictions to monitor data loss due to capacity constraints.</p>
     *
     * <p><b>Troubleshooting:</b></p>
     * <ul>
     *   <li><b>High Evictions</b>: Non-zero <code>evictions</code> (logged as <code>Evictions: missingPathHeuristics=X</code>) indicate many corrupted paths. Increase <code>maxSize</code> or log evicted paths for manual review.</li>
     *   <li><b>Invalid Keys</b>: Non-string or non-JCR keys (not starting with '/') are rejected. Check <code>put</code> calls in <code>countNodes</code>.</li>
     *   <li><b>Missing Values</b>: If heuristics are not found, verify <code>applyHeuristic</code> calls and path additions in <code>countNodes</code>.</li>
     * </ul>
     */
    static class BoundedMap<K, V> {
        private final LinkedHashMap<K, V> map
        private final int maxSize
        private int evictions = 0

        BoundedMap(int maxSize) {
            this.map = new LinkedHashMap<>(maxSize, 0.75f, true)
            this.maxSize = maxSize
        }

        V put(K key, V value) {
            if (!(key instanceof String) || !((String) key).startsWith("/")) {
                return null
            }
            if (map.size() >= maxSize) {
                map.remove(map.keySet().iterator().next())
                evictions++
            }
            return map.put(key, value)
        }

        V get(K key) { map.get(key) }
        Set<K> keySet() { map.keySet() }
        int size() { map.size() }
        int getEvictions() { evictions }
    }
}