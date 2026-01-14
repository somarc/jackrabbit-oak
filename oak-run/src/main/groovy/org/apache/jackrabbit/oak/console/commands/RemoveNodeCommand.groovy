import org.apache.jackrabbit.oak.spi.commit.CommitInfo
import org.apache.jackrabbit.oak.spi.commit.EmptyHook
import org.apache.jackrabbit.oak.commons.PathUtils
import org.apache.jackrabbit.oak.console.ConsoleSession
import org.apache.jackrabbit.oak.spi.state.NodeStore
import org.codehaus.groovy.tools.shell.CommandSupport
import org.codehaus.groovy.tools.shell.Groovysh
import groovy.transform.CompileStatic

import java.util.Properties
import java.io.InputStream

/**
 * RemoveNodeCommand (oak-run Groovy shell)
 * 
 * Provides a shell command to safely remove an individual node from a SegmentStore (TarMK) repository.
 * 
 * See :help remove-node for more information.
 */
@CompileStatic
class RemoveNodeCommand extends CommandSupport {

    static final String COMMAND_NAME = 'remove-node'

    /**
     * Constructs the command with a reference to the shell and sets up the command name.
     *
     * @param shell Active Groovysh instance from oak-run console
     */
    RemoveNodeCommand(Groovysh shell) {
        super(shell, COMMAND_NAME, "rmNode")
        
        // Load properties for description, usage, and help
        loadProperties()
    }
    
    private Properties commandProperties = new Properties()
    
    private void loadProperties() {
        try {
            InputStream is = this.class.getResourceAsStream("RemoveNodeCommand.properties")
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
        return commandProperties.getProperty("command.description", "Removes a single node from the repository")
    }
    
    @Override
    String getUsage() {
        return commandProperties.getProperty("command.usage", "<jcr-path>")
    }
    
    @Override
    String getHelp() {
        return commandProperties.getProperty("command.help", "Removes a single node at the specified JCR path")
    }

    /**
     * Executes the remove-node command, removing a single JCR node if it exists.
     * Prints a result message to the shell.
     *
     * @param args List of command-line arguments (expects first element as the JCR path)
     * @return null
     */
    @Override
    Object execute(List<String> args) {
        if (args.isEmpty()) throw new IllegalArgumentException(getHelp())

        String path = args[0]
        ConsoleSession session = getSession()
        NodeStore nodeStore = session.getStore()

        boolean result = removeNode(nodeStore, path)
        if (result) {
            io.out.println("Node at path '${path}' removed successfully.")
        } else {
            io.out.println("Node at path '${path}' does not exist or could not be removed (may not exist or too shallow).")
        }
        return null
    }

    /**
     * Removes the node at the specified JCR path if it exists and is not the root.
     * 
     * @param nodeStore The active NodeStore (repository backend)
     * @param path The JCR path of the node to be removed
     * @return true if the node existed and was deleted, false otherwise
     */
    private boolean removeNode(NodeStore nodeStore, String path) {
        // Safety: block deletion of root "" or top-level node "/foo"
        int depth = PathUtils.elements(path).size()
        if (depth <= 1) {
            io.out.println("[ERROR] Refusing to delete top-level or root node: '${path}'")
            return false
        }

        def rootBuilder = nodeStore.root.builder()
        def targetBuilder = rootBuilder

        PathUtils.elements(path).each { element ->
            targetBuilder = targetBuilder.getChildNode(element)
        }

        if (targetBuilder.exists()) {
            targetBuilder.remove()
            nodeStore.merge(rootBuilder, EmptyHook.INSTANCE, CommitInfo.EMPTY)
            return true
        } else {
            return false
        }
    }

    /**
     * Obtain the current oak-run ConsoleSession from shell variables.
     *
     * @return active ConsoleSession
     */
    private ConsoleSession getSession() {
        return (ConsoleSession) variables.get("session")
    }

}
