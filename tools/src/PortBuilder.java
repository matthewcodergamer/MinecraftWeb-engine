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
        tool.setObfuscated(Boolean.getBoolean("port.minify"));
        tool.setOptimizationLevel(TeaVMOptimizationLevel.SIMPLE);
        tool.setFastDependencyAnalysis(Boolean.getBoolean("port.fast"));
        tool.setStrict(false);
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
        System.out.println("classes compiled: " + tool.getClasses().size() + ", severe problems: " + severe);
        System.exit(severe == 0 ? 0 : 2);
    }
}
