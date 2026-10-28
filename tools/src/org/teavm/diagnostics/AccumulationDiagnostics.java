package org.teavm.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.teavm.model.CallLocation;

/**
 * Build-tool replacement of TeaVM's AccumulationDiagnostics (shadows the one in teavm-core on the builder's
 * classpath). Linkage problems - a referenced class, method or field that does not exist - are recorded but not
 * treated as fatal: TeaVM then compiles those references to code that throws NoClassDefFoundError /
 * NoSuchMethodError / NoSuchFieldError when executed, which is how a JVM links lazily. Every such problem is
 * still printed by PortBuilder (porting worklist); all other errors stay fatal.
 */
public class AccumulationDiagnostics implements Diagnostics, ProblemProvider {
    private final List<Problem> problems = new ArrayList<>();
    private final List<Problem> severeProblems = new ArrayList<>();

    static boolean isLinkageProblem(String text) {
        return text.contains("was not found") || text.contains("which is missing in the classpath");
    }

    @Override
    public void error(CallLocation location, String error, Object... params) {
        Problem p = new Problem(ProblemSeverity.ERROR, location, error, params);
        problems.add(p);
        if (!Boolean.getBoolean("port.lenientLinkage") || !isLinkageProblem(error)) {
            severeProblems.add(p);
        }
    }

    @Override
    public void warning(CallLocation location, String error, Object... params) {
        problems.add(new Problem(ProblemSeverity.WARNING, location, error, params));
    }

    @Override
    public List<Problem> getProblems() {
        return Collections.unmodifiableList(problems);
    }

    @Override
    public List<Problem> getSevereProblems() {
        return Collections.unmodifiableList(severeProblems);
    }
}
