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
    /** Prints each reachable method matching a prefix and its shortest caller chain to the entry point. */
    private static void explain(TeaVMTool tool, String prefix) {
        org.teavm.dependency.DependencyInfo info = tool.getDependencyInfo();
        org.teavm.callgraph.CallGraph graph = info.getCallGraph();
        java.util.TreeMap<String, org.teavm.model.MethodReference> targets = new java.util.TreeMap<>();
        for (org.teavm.model.MethodReference m : info.getReachableMethods()) {
            if (m.getClassName().startsWith(prefix)) {
                targets.put(m.toString(), m);
            }
        }
        System.out.println("WHY " + prefix + " (" + targets.size() + " reachable methods)");
        String entry = tool.getMainClass();
        for (org.teavm.model.MethodReference target : targets.values()) {
            java.util.Map<org.teavm.model.MethodReference, org.teavm.model.MethodReference> next = new java.util.HashMap<>();
            java.util.ArrayDeque<org.teavm.model.MethodReference> queue = new java.util.ArrayDeque<>();
            next.put(target, null);
            queue.add(target);
            org.teavm.model.MethodReference found = null;
            while (!queue.isEmpty()) {
                org.teavm.model.MethodReference m = queue.poll();
                if (m.getClassName().equals(entry)) {
                    found = m;
                    break;
                }
                org.teavm.callgraph.CallGraphNode node = graph.getNode(m);
                if (node == null) {
                    continue;
                }
                for (org.teavm.callgraph.CallSite site : node.getCallerCallSites()) {
                    for (org.teavm.callgraph.CallGraphNode caller : site.getCallers()) {
                        org.teavm.model.MethodReference callerMethod = caller.getMethod();
                        if (!next.containsKey(callerMethod)) {
                            next.put(callerMethod, m);
                            queue.add(callerMethod);
                        }
                    }
                }
            }
            if (found == null) {
                System.out.println("   " + target + " <- (no caller chain to entry; class init/virtual dispatch may be involved)");
            } else {
                System.out.println("   CHAIN " + target);
                org.teavm.model.MethodReference cur = found;
                while (cur != null) {
                    System.out.println("      " + cur);
                    cur = next.get(cur);
                }
            }
        }
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
        // FULL gives TeaVM's best JavaScript/WASM output (inlining, escape analysis,
        // dead-code elimination). It costs build time, not runtime, so it is the
        // default for release artifacts. PORT_OPT=ADVANCED|SIMPLE makes an
        // incremental debugging build possible without editing this file.
        String opt = System.getProperty("port.opt", System.getenv().getOrDefault("PORT_OPT", "FULL"));
        tool.setOptimizationLevel(TeaVMOptimizationLevel.valueOf(opt.trim().toUpperCase(java.util.Locale.ROOT)));
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

        int severe = tool.getProblemProvider().getSevereProblems().size();
        int linkage = 0;
        java.util.Set<Problem> severeSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        severeSet.addAll(tool.getProblemProvider().getSevereProblems());
        // Emit the complete severe-error worklist separately from lazy LINK diagnostics.
        // The build script's compact tail can otherwise hide these when LINK output is large.
        System.out.println("=== SEVERE_EXACT_BEGIN count=" + severe + " ===");
        for (Problem p : tool.getProblemProvider().getSevereProblems()) {
            StringBuilder sb = new StringBuilder("SEVERE_EXACT ");
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
        System.out.println("=== SEVERE_EXACT_END ===");
        for (Problem p : tool.getProblemProvider().getProblems()) {
            boolean err = p.getSeverity() == ProblemSeverity.ERROR;
            boolean lazy = err && !severeSet.contains(p);
            if (lazy) {
                linkage++;
            }
            StringBuilder sb = new StringBuilder(lazy ? "LINK  " : err ? "ERROR " : "WARN  ");
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
        // Native (JNI) methods without a browser implementation compile to stubs that fail at run time;
        // list every reachable one so they are part of the worklist.
        int natives = 0;
        org.teavm.model.ClassReaderSource classes = tool.getDependencyInfo().getClassSource();
        java.util.TreeSet<String> nativeList = new java.util.TreeSet<>();
        for (org.teavm.model.MethodReference m : tool.getDependencyInfo().getReachableMethods()) {
            org.teavm.model.MethodReader mr = classes.resolve(m);
            if (mr == null || !mr.hasModifier(org.teavm.model.ElementModifier.NATIVE)) {
                continue;
            }
            if (mr.getAnnotations().get("org.teavm.jso.JSBody") != null
                    || mr.getAnnotations().get("org.teavm.interop.Import") != null
                    || mr.getAnnotations().get("org.teavm.backend.javascript.spi.GeneratedBy") != null
                    || mr.getAnnotations().get("org.teavm.backend.javascript.spi.InjectedBy") != null
                    || mr.getAnnotations().get("org.teavm.jso.JSMethod") != null
                    || m.getClassName().startsWith("java.") || m.getClassName().startsWith("org.teavm.")
                    // JSO consumes @JSBody before this check runs: the port's own bridges are listed by class
                    || m.getClassName().startsWith("mcport.") || m.getClassName().equals("org.lwjgl.system.JNI")) {
                continue;
            }
            nativeList.add(m.toString());
        }
        for (String n : nativeList) {
            System.out.println("NATIVE " + n);
            natives++;
        }
        System.out.println("unimplemented reachable native methods: " + natives);
        String why = System.getProperty("port.why");
        if (why != null) {
            for (String prefix : why.split(",")) {
                explain(tool, prefix);
            }
        }
        System.out.println("classes compiled: " + tool.getClasses().size() + ", severe problems: " + severe
                + ", lazy linkage errors (throw at run time): " + linkage);
        System.exit(severe == 0 ? 0 : 2);
    }
}
