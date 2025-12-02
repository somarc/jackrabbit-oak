/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.run.luke;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * LUKE-style GUI for exploring Lucene indexes.
 * 
 * Features:
 * - Index overview (documents, fields, terms)
 * - Field browser with term counts
 * - Term browser with document frequencies
 * - Document viewer
 * - Segment information
 */
public class LukeExplorer extends JFrame {
    
    private static final long serialVersionUID = 1L;
    
    // Index state
    private File indexPath;
    private Directory directory;
    private DirectoryReader reader;
    
    // UI Components
    private JTree fieldTree;
    private JTable termTable;
    private JTextArea detailArea;
    private JTextArea documentArea;
    private JLabel statusLabel;
    private JTabbedPane mainTabs;
    
    // Models
    private DefaultTreeModel fieldTreeModel;
    private DefaultTableModel termTableModel;
    
    public LukeExplorer(File initialPath) {
        super("LUKE - Lucene Index Explorer");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeIndex();
                dispose();
                System.exit(0);
            }
        });
        
        initUI();
        
        if (initialPath != null && initialPath.exists()) {
            openIndex(initialPath);
        } else {
            showOpenDialog();
        }
    }
    
    private void initUI() {
        // Menu bar
        JMenuBar menuBar = new JMenuBar();
        
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic('F');
        
        JMenuItem openItem = new JMenuItem("Open Index...");
        openItem.setAccelerator(KeyStroke.getKeyStroke("control O"));
        openItem.addActionListener(e -> showOpenDialog());
        fileMenu.add(openItem);
        
        JMenuItem closeItem = new JMenuItem("Close Index");
        closeItem.addActionListener(e -> closeIndex());
        fileMenu.add(closeItem);
        
        fileMenu.addSeparator();
        
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> {
            closeIndex();
            dispose();
            System.exit(0);
        });
        fileMenu.add(exitItem);
        
        menuBar.add(fileMenu);
        
        JMenu viewMenu = new JMenu("View");
        viewMenu.setMnemonic('V');
        
        JMenuItem refreshItem = new JMenuItem("Refresh");
        refreshItem.setAccelerator(KeyStroke.getKeyStroke("F5"));
        refreshItem.addActionListener(e -> refreshIndex());
        viewMenu.add(refreshItem);
        
        menuBar.add(viewMenu);
        
        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic('H');
        
        JMenuItem aboutItem = new JMenuItem("About LUKE");
        aboutItem.addActionListener(e -> showAbout());
        helpMenu.add(aboutItem);
        
        menuBar.add(helpMenu);
        
        setJMenuBar(menuBar);
        
        // Main tabbed pane
        mainTabs = new JTabbedPane();
        
        // Overview tab
        JPanel overviewPanel = createOverviewPanel();
        mainTabs.addTab("Overview", overviewPanel);
        
        // Fields tab
        JPanel fieldsPanel = createFieldsPanel();
        mainTabs.addTab("Fields", fieldsPanel);
        
        // Documents tab
        JPanel documentsPanel = createDocumentsPanel();
        mainTabs.addTab("Documents", documentsPanel);
        
        // Segments tab
        JPanel segmentsPanel = createSegmentsPanel();
        mainTabs.addTab("Segments", segmentsPanel);
        
        add(mainTabs, BorderLayout.CENTER);
        
        // Status bar
        statusLabel = new JLabel("No index loaded. Use File > Open Index to begin.");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        add(statusLabel, BorderLayout.SOUTH);
    }
    
    private JPanel createOverviewPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        
        detailArea = new JTextArea();
        detailArea.setEditable(false);
        detailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailArea.setMargin(new Insets(10, 10, 10, 10));
        
        JScrollPane scrollPane = new JScrollPane(detailArea);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    private JPanel createFieldsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        
        // Left: Field tree
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Fields");
        fieldTreeModel = new DefaultTreeModel(root);
        fieldTree = new JTree(fieldTreeModel);
        fieldTree.addTreeSelectionListener(this::onFieldSelected);
        
        JScrollPane treeScroll = new JScrollPane(fieldTree);
        treeScroll.setPreferredSize(new Dimension(300, 0));
        
        // Right: Term table
        String[] columns = {"Term", "Doc Freq", "Total Freq"};
        termTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        termTable = new JTable(termTableModel);
        termTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        termTable.getColumnModel().getColumn(0).setPreferredWidth(400);
        termTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        termTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        
        JScrollPane tableScroll = new JScrollPane(termTable);
        
        // Control panel
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel maxTermsLabel = new JLabel("Max terms:");
        JSpinner maxTermsSpinner = new JSpinner(new SpinnerNumberModel(100, 10, 10000, 100));
        JButton loadTermsBtn = new JButton("Load Terms");
        loadTermsBtn.addActionListener(e -> loadTermsForSelectedField((Integer) maxTermsSpinner.getValue()));
        
        controlPanel.add(maxTermsLabel);
        controlPanel.add(maxTermsSpinner);
        controlPanel.add(loadTermsBtn);
        
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(controlPanel, BorderLayout.NORTH);
        rightPanel.add(tableScroll, BorderLayout.CENTER);
        
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, rightPanel);
        splitPane.setDividerLocation(300);
        
        panel.add(splitPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    private JPanel createDocumentsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        
        // Top: Document selector
        JPanel selectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel docIdLabel = new JLabel("Document ID:");
        JSpinner docIdSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 1));
        JButton loadDocBtn = new JButton("Load Document");
        JButton sampleBtn = new JButton("Random Sample");
        
        loadDocBtn.addActionListener(e -> loadDocument((Integer) docIdSpinner.getValue()));
        sampleBtn.addActionListener(e -> loadRandomDocument());
        
        selectorPanel.add(docIdLabel);
        selectorPanel.add(docIdSpinner);
        selectorPanel.add(loadDocBtn);
        selectorPanel.add(sampleBtn);
        
        // Document content
        documentArea = new JTextArea();
        documentArea.setEditable(false);
        documentArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        documentArea.setMargin(new Insets(10, 10, 10, 10));
        
        JScrollPane scrollPane = new JScrollPane(documentArea);
        
        panel.add(selectorPanel, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    private JPanel createSegmentsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        
        // Segment table
        String[] columns = {"Segment", "Documents", "Deletions", "Del %", "Size"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable segmentTable = new JTable(model);
        segmentTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        
        JScrollPane scrollPane = new JScrollPane(segmentTable);
        
        // Refresh button
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refreshBtn = new JButton("Refresh Segments");
        refreshBtn.addActionListener(e -> {
            if (directory != null) {
                loadSegmentInfo(model);
            }
        });
        buttonPanel.add(refreshBtn);
        
        panel.add(buttonPanel, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    private void showOpenDialog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Lucene Index Directory");
        
        if (indexPath != null) {
            chooser.setCurrentDirectory(indexPath.getParentFile());
        }
        
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            openIndex(chooser.getSelectedFile());
        }
    }
    
    private void openIndex(File path) {
        closeIndex();
        
        // Find actual index directory
        File actualDir = findActualIndexDirectory(path);
        if (actualDir == null) {
            JOptionPane.showMessageDialog(this,
                    "No valid Lucene index found at:\n" + path.getAbsolutePath() +
                    "\n\nLook for directories containing 'segments_*' files.",
                    "Invalid Index", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        try {
            this.indexPath = path;
            this.directory = FSDirectory.open(actualDir);
            this.reader = DirectoryReader.open(directory);
            
            setTitle("LUKE - " + path.getAbsolutePath());
            statusLabel.setText(String.format("Index: %s | Documents: %,d | Fields: %d",
                    path.getName(), reader.numDocs(), countFields()));
            
            loadOverview();
            loadFieldTree();
            
            // Load segments in background
            SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    // Find segment table model
                    Component segPanel = mainTabs.getComponentAt(3);
                    if (segPanel instanceof JPanel) {
                        for (Component c : ((JPanel) segPanel).getComponents()) {
                            if (c instanceof JScrollPane) {
                                JViewport viewport = ((JScrollPane) c).getViewport();
                                if (viewport.getView() instanceof JTable) {
                                    loadSegmentInfo((DefaultTableModel) ((JTable) viewport.getView()).getModel());
                                }
                            }
                        }
                    }
                    return null;
                }
            };
            worker.execute();
            
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "Error opening index: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }
    
    private void closeIndex() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            reader = null;
        }
        if (directory != null) {
            try {
                directory.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            directory = null;
        }
        indexPath = null;
        
        // Clear UI
        detailArea.setText("");
        documentArea.setText("");
        termTableModel.setRowCount(0);
        fieldTreeModel.setRoot(new DefaultMutableTreeNode("Fields"));
        statusLabel.setText("No index loaded.");
        setTitle("LUKE - Lucene Index Explorer");
    }
    
    private void refreshIndex() {
        if (indexPath != null) {
            File path = indexPath;
            closeIndex();
            openIndex(path);
        }
    }
    
    private File findActualIndexDirectory(File path) {
        if (!path.exists() || !path.isDirectory()) {
            return null;
        }
        
        if (hasSegmentsFile(path)) {
            return path;
        }
        
        File dataDir = new File(path, "data");
        if (dataDir.exists() && hasSegmentsFile(dataDir)) {
            return dataDir;
        }
        
        return null;
    }
    
    private boolean hasSegmentsFile(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.getName().startsWith("segments") && !f.getName().equals("segments.gen")) {
                return true;
            }
        }
        return false;
    }
    
    private int countFields() {
        if (reader == null) return 0;
        try {
            Fields fields = MultiFields.getFields(reader);
            if (fields == null) return 0;
            int count = 0;
            for (@SuppressWarnings("unused") String f : fields) count++;
            return count;
        } catch (IOException e) {
            return 0;
        }
    }
    
    private void loadOverview() {
        if (reader == null) return;
        
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════════════════════\n");
        sb.append("INDEX OVERVIEW\n");
        sb.append("═══════════════════════════════════════════════════════════════════════════\n\n");
        
        sb.append(String.format("Path: %s%n", indexPath.getAbsolutePath()));
        sb.append(String.format("Size: %s%n%n", humanReadableSize(getFolderSize(indexPath))));
        
        sb.append("DOCUMENT STATISTICS:\n");
        sb.append(String.format("  Total Documents: %,d%n", reader.numDocs()));
        sb.append(String.format("  Max Doc ID: %,d%n", reader.maxDoc()));
        sb.append(String.format("  Deleted Documents: %,d%n", reader.numDeletedDocs()));
        if (reader.maxDoc() > 0) {
            double delRatio = (reader.numDeletedDocs() * 100.0) / reader.maxDoc();
            sb.append(String.format("  Deletion Ratio: %.1f%%%n", delRatio));
        }
        sb.append("\n");
        
        try {
            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                Map<String, Long> fieldTerms = new TreeMap<>();
                long totalTerms = 0;
                
                for (String fieldName : fields) {
                    Terms terms = fields.terms(fieldName);
                    if (terms != null) {
                        long termCount = terms.size();
                        if (termCount == -1) {
                            termCount = countTermsManually(terms);
                        }
                        fieldTerms.put(fieldName, termCount);
                        totalTerms += termCount;
                    }
                }
                
                sb.append("FIELD STATISTICS:\n");
                sb.append(String.format("  Total Fields: %d%n", fieldTerms.size()));
                sb.append(String.format("  Total Terms: %,d%n%n", totalTerms));
                
                // Top 10 fields
                List<Map.Entry<String, Long>> sorted = new ArrayList<>(fieldTerms.entrySet());
                sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                
                sb.append("TOP 10 LARGEST FIELDS:\n");
                sb.append(String.format("  %-45s %15s %8s%n", "Field", "Terms", "%"));
                sb.append("  " + repeatChar('-', 70) + "\n");
                
                int count = 0;
                for (Map.Entry<String, Long> entry : sorted) {
                    if (count++ >= 10) break;
                    String display = entry.getKey();
                    if (display.length() > 43) {
                        display = display.substring(0, 40) + "...";
                    }
                    double pct = totalTerms > 0 ? (entry.getValue() * 100.0 / totalTerms) : 0;
                    sb.append(String.format("  %-45s %,15d %7.2f%%%n", display, entry.getValue(), pct));
                }
            }
        } catch (IOException e) {
            sb.append("Error loading field statistics: ").append(e.getMessage());
        }
        
        sb.append("\n═══════════════════════════════════════════════════════════════════════════\n");
        
        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }
    
    private void loadFieldTree() {
        if (reader == null) return;
        
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Fields");
        
        try {
            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                List<FieldNode> fieldList = new ArrayList<>();
                
                for (String fieldName : fields) {
                    Terms terms = fields.terms(fieldName);
                    long termCount = 0;
                    if (terms != null) {
                        termCount = terms.size();
                        if (termCount == -1) {
                            // Don't count manually for tree - too slow
                            termCount = -1;
                        }
                    }
                    fieldList.add(new FieldNode(fieldName, termCount));
                }
                
                // Sort by term count descending
                fieldList.sort((a, b) -> Long.compare(b.termCount, a.termCount));
                
                for (FieldNode fn : fieldList) {
                    String display = fn.termCount >= 0 
                            ? String.format("%s (%,d terms)", fn.name, fn.termCount)
                            : fn.name;
                    DefaultMutableTreeNode node = new DefaultMutableTreeNode(new FieldNodeWrapper(fn.name, display));
                    root.add(node);
                }
            }
        } catch (IOException e) {
            root.add(new DefaultMutableTreeNode("Error: " + e.getMessage()));
        }
        
        fieldTreeModel.setRoot(root);
        fieldTree.expandRow(0);
    }
    
    private void onFieldSelected(TreeSelectionEvent e) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) fieldTree.getLastSelectedPathComponent();
        if (node == null) return;
        
        Object userObject = node.getUserObject();
        if (userObject instanceof FieldNodeWrapper) {
            String fieldName = ((FieldNodeWrapper) userObject).fieldName;
            statusLabel.setText("Selected field: " + fieldName + " - Click 'Load Terms' to view terms");
        }
    }
    
    private void loadTermsForSelectedField(int maxTerms) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) fieldTree.getLastSelectedPathComponent();
        if (node == null || reader == null) return;
        
        Object userObject = node.getUserObject();
        if (!(userObject instanceof FieldNodeWrapper)) return;
        
        String fieldName = ((FieldNodeWrapper) userObject).fieldName;
        
        // Load in background
        statusLabel.setText("Loading terms for " + fieldName + "...");
        termTableModel.setRowCount(0);
        
        SwingWorker<List<Object[]>, Void> worker = new SwingWorker<List<Object[]>, Void>() {
            @Override
            protected List<Object[]> doInBackground() throws Exception {
                List<Object[]> rows = new ArrayList<>();
                
                Terms terms = MultiFields.getTerms(reader, fieldName);
                if (terms == null) return rows;
                
                List<TermData> termList = new ArrayList<>();
                TermsEnum termsEnum = terms.iterator(null);
                BytesRef term;
                
                while ((term = termsEnum.next()) != null) {
                    termList.add(new TermData(
                            term.utf8ToString(),
                            termsEnum.docFreq(),
                            termsEnum.totalTermFreq()
                    ));
                    
                    // Keep only top terms
                    if (termList.size() > maxTerms * 2) {
                        termList.sort((a, b) -> Integer.compare(b.docFreq, a.docFreq));
                        termList = new ArrayList<>(termList.subList(0, maxTerms));
                    }
                }
                
                termList.sort((a, b) -> Integer.compare(b.docFreq, a.docFreq));
                if (termList.size() > maxTerms) {
                    termList = termList.subList(0, maxTerms);
                }
                
                for (TermData td : termList) {
                    String display = td.term.replaceAll("[\\p{Cntrl}]", "?");
                    if (display.length() > 100) {
                        display = display.substring(0, 97) + "...";
                    }
                    rows.add(new Object[]{display, td.docFreq, td.totalFreq});
                }
                
                return rows;
            }
            
            @Override
            protected void done() {
                try {
                    List<Object[]> rows = get();
                    for (Object[] row : rows) {
                        termTableModel.addRow(row);
                    }
                    statusLabel.setText(String.format("Loaded %d terms for field: %s", rows.size(), fieldName));
                } catch (Exception e) {
                    statusLabel.setText("Error loading terms: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }
    
    private void loadDocument(int docId) {
        if (reader == null) {
            documentArea.setText("No index loaded.");
            return;
        }
        
        if (docId < 0 || docId >= reader.maxDoc()) {
            documentArea.setText(String.format("Invalid document ID: %d (valid range: 0-%d)", docId, reader.maxDoc() - 1));
            return;
        }
        
        try {
            Document doc = reader.document(docId);
            if (doc == null) {
                documentArea.setText("Document #" + docId + " is deleted.");
                return;
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("═══════════════════════════════════════════════════════════════════════════\n");
            sb.append(String.format("DOCUMENT #%d%n", docId));
            sb.append("═══════════════════════════════════════════════════════════════════════════\n\n");
            
            for (IndexableField field : doc.getFields()) {
                String value = field.stringValue();
                if (value != null) {
                    if (value.length() > 500) {
                        value = value.substring(0, 497) + "...";
                    }
                    value = value.replaceAll("[\\p{Cntrl}]", " ");
                    sb.append(String.format("%s: %s%n", field.name(), value));
                } else if (field.binaryValue() != null) {
                    sb.append(String.format("%s: [binary, %d bytes]%n", field.name(), field.binaryValue().length));
                } else if (field.numericValue() != null) {
                    sb.append(String.format("%s: %s%n", field.name(), field.numericValue()));
                }
            }
            
            sb.append("\n═══════════════════════════════════════════════════════════════════════════\n");
            
            documentArea.setText(sb.toString());
            documentArea.setCaretPosition(0);
            
        } catch (IOException e) {
            documentArea.setText("Error loading document: " + e.getMessage());
        }
    }
    
    private void loadRandomDocument() {
        if (reader == null || reader.numDocs() == 0) {
            documentArea.setText("No documents in index.");
            return;
        }
        
        Random rand = new Random();
        int docId = rand.nextInt(reader.maxDoc());
        loadDocument(docId);
    }
    
    private void loadSegmentInfo(DefaultTableModel model) {
        if (directory == null) return;
        
        SwingUtilities.invokeLater(() -> model.setRowCount(0));
        
        try {
            SegmentInfos segInfos = new SegmentInfos();
            segInfos.read(directory);
            
            for (SegmentCommitInfo info : segInfos) {
                int docs = info.info.getDocCount();
                int deletes = info.getDelCount();
                double delPercent = docs > 0 ? (deletes * 100.0 / docs) : 0;
                
                final Object[] row = {
                        info.info.name,
                        docs,
                        deletes,
                        String.format("%.1f%%", delPercent),
                        "N/A"  // Size estimation not easy
                };
                
                SwingUtilities.invokeLater(() -> model.addRow(row));
            }
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> 
                    model.addRow(new Object[]{"Error", e.getMessage(), "", "", ""}));
        }
    }
    
    private void showAbout() {
        String message = "LUKE - Lucene Index Explorer\n" +
                "Part of Apache Jackrabbit Oak\n\n" +
                "Inspired by the original LUKE by Andrzej Bialecki\n\n" +
                "Features:\n" +
                "  - Browse index structure\n" +
                "  - Analyze fields and terms\n" +
                "  - View documents\n" +
                "  - Inspect segments\n\n" +
                "For more information, see:\n" +
                "https://jackrabbit.apache.org/oak/";
        
        JOptionPane.showMessageDialog(this, message, "About LUKE", JOptionPane.INFORMATION_MESSAGE);
    }
    
    // Utility methods
    private long countTermsManually(Terms terms) throws IOException {
        long count = 0;
        TermsEnum te = terms.iterator(null);
        while (te.next() != null) {
            count++;
        }
        return count;
    }
    
    private long getFolderSize(File folder) {
        long size = 0;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += getFolderSize(file);
                }
            }
        }
        return size;
    }
    
    private String humanReadableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    private String repeatChar(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
    
    // Inner classes
    private static class FieldNode {
        final String name;
        final long termCount;
        
        FieldNode(String name, long termCount) {
            this.name = name;
            this.termCount = termCount;
        }
    }
    
    private static class FieldNodeWrapper {
        final String fieldName;
        final String displayText;
        
        FieldNodeWrapper(String fieldName, String displayText) {
            this.fieldName = fieldName;
            this.displayText = displayText;
        }
        
        @Override
        public String toString() {
            return displayText;
        }
    }
    
    private static class TermData {
        final String term;
        final int docFreq;
        final long totalFreq;
        
        TermData(String term, int docFreq, long totalFreq) {
            this.term = term;
            this.docFreq = docFreq;
            this.totalFreq = totalFreq;
        }
    }
}

