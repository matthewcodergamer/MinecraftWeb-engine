import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.teavm.diagnostics.Problem;
import org.teavm.diagnostics.ProblemSeverity;
import org.teavm.tooling.ConsoleTeaVMToolLog;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.vm.TeaVMOptimizationLevel;

/**
 * Drives TeaVM over the real Minecraft client bytecode.
 *
 * args: target(js|wasm) mainClass outDir outFile classpathEntry...
 * Exits non-zero if TeaVM reports severe problems; every problem is printed
 * so missing classes/methods form the porting worklist.
 */
public class PortBuilder {
    /** Prints the shortest call chain from the entry point into any method of a class matching prefix. */
    private static void explain(TeaVMTool tool, String prefix) {
        org.teavm.dependency.DependencyInfo info = tool.getDependencyInfo();
        org.teavm.callgraph.CallGraph graph = info.getCallGraph();
        java.util.Map<org.teavm.model.MethodReference, org.teavm.model.MethodReference> next = new java.util.HashMap<>();
        java.util.ArrayDeque<org.teavm.model.MethodReference> queue = new java.util.ArrayDeque<>();
        for (org.teavm.model.MethodReference m : info.getReachableMethods()) {
            if (m.getClassName().startsWith(prefix)) {
                next.put(m, null);
                queue.add(m);
            }
        }
        System.out.println("WHY " + prefix + " (" + queue.size() + " reachable methods)");
        String entry = tool.getMainClass();
        while (!queue.isEmpty()) {
            org.teavm.model.MethodReference m = queue.poll();
            if (m.getClassName().equals(entry)) {
                org.teavm.model.MethodReference cur = m;
                while (cur != null) {
                    System.out.println("   " + cur);
                    cur = next.get(cur);
                }
                return;
            }
            org.teavm.callgraph.CallGraphNode node = graph.getNode(m);
            if (node == null) {
                continue;
            }
            for (org.teavm.callgraph.CallSite site : node.getCallerCallSites()) {
                for (org.teavm.callgraph.CallGraphNode caller : site.getCallers()) {
                    org.teavm.model.MethodReference c = caller.getMethod();
                    if (!next.containsKey(c)) {
                        next.put(c, m);
                        queue.add(c);
                    }
                }
            }
        }
        System.out.println("   (no chain from entry found - reached via class init or virtual dispatch)");
    }

    public static void main(String[] args) throws Exception {
        String target = args[0];
        TeaVMTool tool = new TeaVMTool();
        tool.setLog(new ConsoleTeaVMToolLog(false));
        tool.setTargetType("wasm".equals(target) ? TeaVMTargetType.WEBASSEMBLY_GC : TeaVMTargetType.JAVASCRIPT);
        tool.setMainClass(args[1]);
        tool.setTargetDirectory(new File(args[2]));
        tool.setTargetFileName(args[3]);
        List<File> cp = new ArrayList<>();
        for (int i = 4; i < args.length; i++) {
            cp.add(new File(args[i]));
        }
        tool.setClassPath(cp);
        tool.getTransformers().add("PortTransformer");
        tool.setObfuscated(Boolean.getBoolean("port.minify"));
        tool.setOptimizationLevel(TeaVMOptimizationLevel.SIMPLE);
        tool.setFastDependencyAnalysis(Boolean.getBoolean("port.fast"));
        tool.setStrict(false);
        String cache = System.getProperty("port.cache");
        if (cache != null) {
            tool.setIncremental(true);
            tool.setCacheDirectory(new File(cache));
        }
        tool.setDebugInformationGenerated(false);
        tool.setSourceMapsFileGenerated(false);
        tool.generate();

        int severe = 0;
        for (Problem p : tool.getProblemProvider().getProblems()) {
            boolean err = p.getSeverity() == ProblemSeverity.ERROR;
            if (err) {
                severe++;
            }
            StringBuilder sb = new StringBuilder(err ? "ERROR " : "WARN  ");
            sb.append(p.getText());
            Object[] params = p.getParams();
            if (params != null) {
                for (Object o : params) {
                    sb.append(" | ").append(o);
                }
            }
            if (p.getLocation() != null && p.getLocation().getMethod() != null) {
                sb.append("  @ ").append(p.getLocation().getMethod());
            }
            System.out.println(sb);
        }
        String why = System.getProperty("port.why");
        if (why != null) {
            for (String prefix : why.split(",")) {
                explain(tool, prefix);
            }
        }
        System.out.println("classes compiled: " + tool.getClasses().size() + ", severe problems: " + severe);
        System.exit(severe == 0 ? 0 : 2);
    }
}
