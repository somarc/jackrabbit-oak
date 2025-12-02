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

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.jackrabbit.oak.run.commons.Command;

import javax.swing.*;
import java.io.File;

/**
 * Command to launch the LUKE GUI browser.
 * 
 * Usage:
 *   java -jar oak-run-luke.jar gui /path/to/index
 */
public class LukeGuiCommand implements Command {

    @Override
    public void execute(String... args) throws Exception {
        OptionParser parser = new OptionParser();
        
        OptionSpec<?> helpOpt = parser.accepts("help", "Show help").forHelp();
        OptionSpec<File> nonOptions = parser.nonOptions("index-path").ofType(File.class);
        
        OptionSet options = parser.parse(args);
        
        if (options.has(helpOpt)) {
            printUsage();
            parser.printHelpOn(System.out);
            return;
        }
        
        File indexPath = null;
        if (!options.valuesOf(nonOptions).isEmpty()) {
            indexPath = options.valuesOf(nonOptions).get(0);
        }
        
        // Launch GUI on the Swing event dispatch thread
        final File finalIndexPath = indexPath;
        SwingUtilities.invokeLater(() -> {
            try {
                initLookAndFeel();
                LukeExplorer explorer = new LukeExplorer(finalIndexPath);
                explorer.setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, 
                        "Error starting LUKE GUI: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                e.printStackTrace();
            }
        });
    }
    
    private void printUsage() {
        System.out.println("LUKE GUI Browser - Interactive Lucene index exploration");
        System.out.println("=======================================================");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar oak-run-luke.jar gui [path-to-index]");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Launch with file chooser");
        System.out.println("  java -jar oak-run-luke.jar gui");
        System.out.println();
        System.out.println("  # Open specific index");
        System.out.println("  java -jar oak-run-luke.jar gui crx-quickstart/repository/index/damAssetLucene-*/data");
        System.out.println();
    }
    
    private void initLookAndFeel() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    return;
                }
            }
            // Fall back to system look and feel
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Ignore - use default
        }
    }
}
