import org.apache.jackrabbit.oak.spi.commit.CommitInfo
import org.apache.jackrabbit.oak.spi.commit.EmptyHook
import org.apache.jackrabbit.oak.commons.PathUtils
import org.apache.jackrabbit.oak.console.ConsoleSession
import org.apache.jackrabbit.oak.spi.state.NodeStore
import org.codehaus.groovy.tools.shell.CommandSupport
import org.codehaus.groovy.tools.shell.Groovysh
import groovy.transform.CompileStatic

@CompileStatic
class RemoveNodesCommand extends CommandSupport {
    static final String COMMAND_NAME = 'remove-nodes'

    RemoveNodesCommand(Groovysh shell) {
        super(shell, COMMAND_NAME, "rmNodes")
    }

    @Override
    Object execute(List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("Usage: rmNodes input_file.txt")
        }

        String inputFilePath = args[0]
        ConsoleSession session = getSession()
        NodeStore nodeStore = session.getStore()

        File inputFile = new File(inputFilePath)
        if (!inputFile.exists()) {
            throw new IllegalArgumentException("Input file '${inputFilePath}' does not exist.")
        }

        inputFile.eachLine { line ->
            if (line.startsWith("Warning: unable to read node")) {
                String[] parts = line.split(" due to ")
                String path = parts[0].replace("Warning: unable to read node", "").trim()
                
                boolean result = removeNode(nodeStore, path)
                if (result) {
                    io.out.println("Node at path '${path}' removed successfully.")
                } else {
                    io.out.println("Node at path '${path}' does not exist or could not be removed.")
                }
            } else if (line.startsWith("Blob Path : ")) {
                String path = line.substring(line.indexOf(":") + 1, line.indexOf("==>")).trim();
            
                boolean result = removeNode(nodeStore, path)
                if (result) {
                    io.out.println("Node at path '${path}' removed successfully.")
                } else {
                    io.out.println("Node at path '${path}' does not exist or could not be removed.")
                }
            }
        }

        return null
    }

    private boolean removeNode(NodeStore nodeStore, String path) {
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

    private ConsoleSession getSession() {
        return (ConsoleSession) variables.get("session")
    }
}