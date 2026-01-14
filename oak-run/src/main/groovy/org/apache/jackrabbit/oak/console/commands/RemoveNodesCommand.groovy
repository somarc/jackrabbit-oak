import org.apache.jackrabbit.oak.spi.commit.CommitInfo
import org.apache.jackrabbit.oak.spi.commit.EmptyHook
import org.apache.jackrabbit.oak.commons.PathUtils
import org.apache.jackrabbit.oak.console.ConsoleSession
import org.apache.jackrabbit.oak.spi.state.NodeStore
import org.codehaus.groovy.tools.shell.CommandSupport
import org.codehaus.groovy.tools.shell.Groovysh

import java.util.Properties
import java.io.InputStream
import groovy.transform.CompileStatic
import java.text.SimpleDateFormat
import java.util.regex.Pattern
import java.util.regex.Matcher

/**
 * RemoveNodesCommand (oak-run Groovy shell)
 *
 * Safely deletes JCR nodes based on input file, following DAM/rendition and path rules.
 * Logs to a batch log file for audit/review and quick shell feedback.
 */
@CompileStatic
class RemoveNodesCommand extends CommandSupport {

    static final String COMMAND_NAME = 'remove-nodes'
    static final int MIN_DELETE_DEPTH = 3

    // Patterns for input parsing and classifications
    static final Pattern NODE_PATTERN =
        Pattern.compile('([0-9a-f]{2}(?:[\\\\/]+)[0-9a-f]{2}(?:[\\\\/]+)[0-9a-f]{2}(?:[\\\\/]+)[0-9a-f]{64}),(.*)')
    static final Pattern MISSING_BLOB_PATTERN =
        Pattern.compile('Warning: Missing blob at (.+?): org\\.apache\\.jackrabbit\\.core\\.data\\.DataStoreException: Record')
    static final String SEGMENT_NOT_FOUND_PREFIX = "Warning: Missing segment at"
    static final String NODE_UNREADABLE_PREFIX = "Warning: Unable to read node"
    static final Pattern DAM_ORIGINAL_PATTERN =
        Pattern.compile('^/content/dam/.+?/jcr:content/renditions/original/jcr:content$')
    static final Pattern DAM_RENDITION_PATTERN =
        Pattern.compile('^(/content/dam/.+?/jcr:content/renditions/[^/]+)/jcr:content$')
    static final Pattern FOLDER_THUMB_PATTERN =
        Pattern.compile('^(/content/dam/.+?/jcr:content/folderThumbnail)/jcr:content$')
    static final Pattern OAK_INDEX_BINARY_PATTERN =
        Pattern.compile('^/oak:index/[^/]+/:(data|suggest-data)(/.*)?$')

    // Counters and lists for summary and reporting
    int damOriginalCount = 0, damRenditionCount = 0, folderThumbCount = 0, versionStoreCount = 0
    int tmpVarCount = 0, etcPackageCount = 0, fallbackCount = 0
    int indexBinaryCount = 0
    int missingSegments = 0, missingBlobs = 0

    List<String> fallbackPaths = []
    List<String> indexBinaryPaths = []
    Map<String, Integer> segmentIdCounts = [:]
    Map<String, Integer> deleteTypeCounts = [:]
    boolean dryRun = false

    /** Writer for batch log output */
    private PrintWriter logWriter
    /** In-memory buffer for batching log messages before write */
    private final List<String> logBuffer
    /** Path to the log file for reporting in shell */
    private String logFilePath

    /**
     * Constructs the command and sets up the batch log file, buffer, and log file path.
     */
    RemoveNodesCommand(Groovysh shell) {
        super(shell, COMMAND_NAME, "rmNodes")
        // Initialize without creating log file - will be created in execute()
        logFilePath = null
        logWriter = null
        logBuffer = []
        
        // Load properties for description, usage, and help
        loadProperties()
    }
    
    private Properties commandProperties = new Properties()
    
    private void loadProperties() {
        try {
            InputStream is = this.class.getResourceAsStream("RemoveNodesCommand.properties")
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
        return commandProperties.getProperty("command.description", "Removes multiple nodes and their subtrees from the repository")
    }
    
    @Override
    String getUsage() {
        return commandProperties.getProperty("command.usage", "<input file> [dry-run] [debug]")
    }
    
    @Override
    String getHelp() {
        return commandProperties.getProperty("command.help", "Removes nodes based on input file with safety checks and logging")
    }


    /**
     * Main entry point – parses the input file and processes deletions using correct rules.
     */
    @Override
    Object execute(List<String> args) {
        // Create log file with fresh timestamp for each execution
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
        File logFile = new File("remove-nodes-${timestamp}.log")
        logFilePath = logFile.getAbsolutePath()
        logWriter = new PrintWriter(logFile, "UTF-8")
        logWriter.println("RemoveNodesCommand Log - Started at " + new Date().toString())
        
        this.dryRun = args.contains("dry-run")
        boolean debug = args.contains("debug")

        log("Raw args: ${args}")
        log("Received options: dryRun=${this.dryRun}, debug=${debug}")

        if (args.isEmpty()) throw new IllegalArgumentException(getHelp())
        String inputFilePath = args[0]
        ConsoleSession session = getSession()
        NodeStore nodeStore = session.getStore()
        File inputFile = new File(inputFilePath)
        if (!inputFile.exists()) throw new IllegalArgumentException("Input file '${inputFilePath}' does not exist.")

        inputFile.eachLine { line ->
            if (debug) log("Processing line: '${line}'")
            String deleteType = ""
            Matcher matcher = NODE_PATTERN.matcher(line)
            if (matcher.matches()) {
                String blobId = matcher.group(1)
                String path = matcher.group(2)
                deleteType = "consistency-check"
                log("[INFO] [${deleteType}] Processing Blob ID: '${blobId}' with JCR Path: '${path}'")
                processNodeRemovalAdvanced(nodeStore, path, deleteType)
            } else if (line.startsWith(NODE_UNREADABLE_PREFIX)) {
                String[] parts = line.split(" due to ")
                String path = parts[0].replace(NODE_UNREADABLE_PREFIX, "").trim()
                deleteType = "count-nodes:node-unreadable"
                log("[INFO] [${deleteType}] Attempting removal at: ${path}")
                processNodeRemovalSimple(nodeStore, path, deleteType)
            } else if (line.startsWith(SEGMENT_NOT_FOUND_PREFIX) || (line.contains("Segment") && line.contains("not found"))) {
                missingSegments++
                String segmentId = extractSegmentId(line)
                segmentIdCounts[segmentId] = (segmentIdCounts[segmentId] ?: 0) + 1
                deleteType = "count-nodes:segment-not-found"
                log("[WARN] [${deleteType}] ${line}")
                incrementDeleteType(deleteType)
            } else if (line.startsWith("Warning: Missing blob at") ||
                      (line.contains("Record") && line.contains("does not exist"))) {
                Matcher blobMatcher = MISSING_BLOB_PATTERN.matcher(line)
                if (blobMatcher.find()) {
                    String path = blobMatcher.group(1).trim()
                    deleteType = "count-nodes:blob-missing"
                    log("[INFO] [${deleteType}] Attempting advanced removal at: ${path}")
                    processNodeRemovalAdvanced(nodeStore, path, deleteType)
                } else {
                    missingBlobs++
                    deleteType = "count-nodes:blob-missing"
                    log("[WARN] [${deleteType}] ${line}")
                    incrementDeleteType(deleteType)
                }
            } else {
                if (debug) log("[NO MATCH] Line did not match any pattern or prefix.")
            }
            // Periodic buffer flush for large files
            if (logBuffer.size() > 200) flushLogBuffer()
        }
        printSummary()
        flushLogBuffer()
        if (logWriter != null) {
            logWriter.println("RemoveNodesCommand Log - Finished at " + new Date().toString())
            logWriter.close()
        }
        io.out.println("RemoveNodesCommand completed. Full detailed log at: ${logFilePath}")
        return null
    }

    /**
     * Handles simple node removal (only a path, no special DAM analysis).
     */
    private void processNodeRemovalSimple(NodeStore nodeStore, String path, String deleteType) {
        if (removeNode(nodeStore, path, deleteType)) {
            log("[DELETE] [${deleteType}] Node at '${path}' removed (simple warning line)." + (dryRun ? " [DRY RUN]" : ""))
        }
    }

    /**
     * Handles advanced node removal, including DAM/original/rendition/folderThumbnail/index logic.
     */
    private void processNodeRemovalAdvanced(NodeStore nodeStore, String path, String deleteType) {
        String whichType = decideNodeToDelete(path)
        String nodePath = getNodeToDelete(path, whichType)
        if (removeNode(nodeStore, nodePath, deleteType)) {
            incrementCounter(whichType, nodePath)
            log("[DELETE] [${deleteType}] Node at '${nodePath}' removed (pattern type: ${whichType})." + (dryRun ? " [DRY RUN]" : ""))
        }
    }

    /**
     * Removes the given JCR node, if safe; logs and tracks via buffer.
     */
    private boolean removeNode(NodeStore nodeStore, String path, String deleteType) {
        if (!isSafeToDelete(path)) {
            String errMsg = "[ERROR] Refusing to delete JCR top-level or shallow path: '${path}'. Check for repository integrity issues."
            log(errMsg)
            incrementDeleteType("skipped-top-level-path")
            return false
        }
        def rootBuilder = nodeStore.root.builder()
        def targetBuilder = rootBuilder
        for (String element : PathUtils.elements(path)) {
            targetBuilder = targetBuilder.getChildNode(element)
        }
        if (targetBuilder.exists()) {
            if (!dryRun) {
                targetBuilder.remove()
                nodeStore.merge(rootBuilder, EmptyHook.INSTANCE, CommitInfo.EMPTY)
            }
            incrementDeleteType(deleteType)
            return true
        } else {
            log("[SKIP] [${deleteType}] Node at '${path}' does not exist.")
            return false
        }
    }

    /**
     * Path is eligible for deletion if it is not root, not a direct child of root,
     * and satisfies MIN_DELETE_DEPTH.
     */
    private boolean isSafeToDelete(String path) {
        if (!path) return false
        int depth = PathUtils.elements(path).size()
        // Block root ("") and top-level nodes (e.g. "/content", "/etc", "/tmp")
        if (depth <= 1) return false
        return depth >= MIN_DELETE_DEPTH
    }

    /**
     * Classifies the path for DAM, rendition, oak:index, or fallback logic. 
     * Removes trailing slash so content/dam/.../jcr:content/ matches rendition/original logic.
     */
    String decideNodeToDelete(String path) {
        path = path?.replaceAll(/\/+$/, "") // Remove all trailing slashes
        if (DAM_ORIGINAL_PATTERN.matcher(path).matches()) return "damOriginal"
        if (DAM_RENDITION_PATTERN.matcher(path).matches()) return "damRendition"
        if (FOLDER_THUMB_PATTERN.matcher(path).matches()) return "folderThumb"
        if (OAK_INDEX_BINARY_PATTERN.matcher(path).matches()) return "indexBinary"
        if (path.startsWith("/jcr:system/jcr:versionStorage/")) return "versionStore"
        if (path.startsWith("/tmp/") || path == "/tmp" || path.startsWith("/var/")) return "tmpVar"
        if (path.startsWith("/etc/packages/")) return "etcPackage"
        return "fallback"
    }

    /**
     * Generates the specific node path to delete based on logical node type.
     */
    String getNodeToDelete(String path, String whichType) {
        switch (whichType) {
            case "damOriginal":
                return path.replaceAll('/jcr:content/renditions/original/jcr:content$', "")
            case "damRendition":
                Matcher m = DAM_RENDITION_PATTERN.matcher(path)
                return m.matches() ? m.group(1) : path
            case "folderThumb":
                Matcher m2 = FOLDER_THUMB_PATTERN.matcher(path)
                return m2.matches() ? m2.group(1) : path
            default:
                return path
        }
    }

    /**
     * Tracks summary counts and examples by node/delete type.
     * Oak index binary & fallback get example path lists for reporting.
     */
    void incrementCounter(String type, String path) {
        switch (type) {
            case "damOriginal": damOriginalCount++; break
            case "damRendition": damRenditionCount++; break
            case "folderThumb": folderThumbCount++; break
            case "versionStore": versionStoreCount++; break
            case "tmpVar": tmpVarCount++; break
            case "etcPackage": etcPackageCount++; break
            case "indexBinary":
                indexBinaryCount++
                indexBinaryPaths << path
                break
            case "fallback":
            default:
                fallbackCount++
                fallbackPaths << path
                break
        }
    }

    /**
     * Tracks tally of operations by delete type for summary.
     */
    private void incrementDeleteType(String type) {
        if (!type) return
        deleteTypeCounts[type] = (deleteTypeCounts[type] ?: 0) + 1
    }

    /**
     * Gets the oak-run session from Groovysh.
     */
    private ConsoleSession getSession() {
        return (ConsoleSession) variables.get("session")
    }

    /**
     * Appends message to log buffer for batch flushing.
     */
    private void log(String msg) {
        logBuffer << msg
    }

    /**
     * Flushes log buffer to file and clears it.
     */
    private void flushLogBuffer() {
        if (!logBuffer.isEmpty() && logWriter != null) {
            logWriter.println(logBuffer.join("\n"))
            logWriter.flush()
            logBuffer.clear()
        }
    }

    /**
     * Writes the final summary to log buffer and eventually logs file.
     */
    private void printSummary() {
        log("\n==== Delete Summary Report ====")
        if (dryRun) {
            log("*** DRY RUN MODE: No nodes were actually deleted. ***")
        }
        log("DAM Asset (original binary removed):        $damOriginalCount")
        log("DAM Derived Rendition:                      $damRenditionCount")
        log("DAM Folder Thumbnail:                       $folderThumbCount")
        log("Versioned Binary Node:                      $versionStoreCount")
        log("tmp/var/audit Node Delete:                  $tmpVarCount")
        log("etc/packages Node Delete:                   $etcPackageCount")
        log("Oak Index Binary Node Delete:               $indexBinaryCount")
        log("Fallback Pattern (manual review needed):    $fallbackCount")
        if (!indexBinaryPaths.isEmpty()) {
            log("\nIndex binary paths deleted (up to 20 shown):")
            indexBinaryPaths.take(20).each { log(it) }
            if (indexBinaryPaths.size() > 20) {
                log("... and ${indexBinaryPaths.size() - 20} more.")
            }
        }
        if (!fallbackPaths.isEmpty()) {
            log("\nFallback pattern paths (up to 20 shown):")
            fallbackPaths.take(20).each { log(it) }
            if (fallbackPaths.size() > 20) {
                log("... and ${fallbackPaths.size() - 20} more.")
            }
        }
        log("\n--- Segment/Blob Missing ---")
        log("Missing segments encountered:               $missingSegments")
        log("Missing blobs encountered:                  $missingBlobs")
        if (!segmentIdCounts.isEmpty()) {
            log("Segment IDs missing (example counts):")
            segmentIdCounts.each { id, cnt -> log("${id}: ${cnt}") }
        }
        log("\n--- Delete Count by Origin/Type ---")
        deleteTypeCounts.each { type, count ->
            log("${type.padRight(36)} : ${count}")
        }
        log("================================\n")
    }

    /**
     * Extracts segment IDs from segment-not-found lines.
     */
    String extractSegmentId(String message) {
        Pattern p = Pattern.compile('(?i)Segment\\s+([0-9a-f\\-]{16,})\\s+not found')
        Matcher matcher = p.matcher(message)
        if (matcher.find()) return matcher.group(1)
        else return "UNKNOWN"
    }
}