import groovy.transform.CompileStatic;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.codehaus.groovy.tools.shell.CommandSupport;
import org.apache.jackrabbit.oak.console.ConsoleSession;
import org.codehaus.groovy.tools.shell.Groovysh;

import java.util.concurrent.atomic.AtomicInteger;

@CompileStatic
class CountNodesCommand extends CommandSupport {
    static final String COMMAND_NAME = 'count-nodes';

    CountNodesCommand(Groovysh shell) {
        super(shell, COMMAND_NAME, 'countNodes');
    }

    @Override
    Object execute(List<String> args) {
        NodeStore store = getSession().getStore();
        NodeState rootState = store.getRoot();

        AtomicInteger count = new AtomicInteger(0);

        countNodes(rootState, "/", 1000000, 1000, count);

        io.out.println("Total nodes: " + count.get());

        return null;
    }

    void countNodes(NodeState n, String path, Integer flush, Long warnAt, AtomicInteger count) {
        int cnt = count.incrementAndGet(); // Increment for each node
        if (cnt % flush == 0) {
            io.out.println("  " + cnt);
        }

        try {
            // Attempt to read all properties to check for corruption
            for (PropertyState prop : (Iterable<PropertyState>) n.getProperties()) {
                prop.getValue(prop.getType()); // Access the property value
            }

            long kids = n.getChildNodeCount(warnAt);
            if (kids >= warnAt) {
                io.out.println(path + " has " + kids + " child nodes");
            }

            for (ChildNodeEntry child : (Iterable<ChildNodeEntry>) n.getChildNodeEntries()) {
                countNodes(child.getNodeState(), path + child.getName() + "/", flush, warnAt, count);
            }
        } catch (Exception e) {
            io.out.println("Warning: unable to read node " + path + " due to " + e.getMessage());
        }
    }

    ConsoleSession getSession() {
        return (ConsoleSession) variables.get("session");
    }
}