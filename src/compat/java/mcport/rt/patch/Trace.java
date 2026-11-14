package mcport.rt.patch;

import mcport.rt.BrowserRuntime;

/**
 * Runtime half of the {@code TRACE} rules in patches/rules.txt.
 *
 * <p>The transformer inserts {@code Trace.mark(id)} at the entry of a method. The id is the
 * number written in the rule, so a startup log such as {@code [JAVA_TRACE] port-stage-104}
 * says exactly which stage the client reached before it hung or crashed. This is a build-time
 * diagnostic only: it never changes game state.</p>
 */
public final class Trace {
    private Trace() {
    }

    public static void mark(int id) {
        BrowserRuntime.report("trace", "port-stage-" + id);
    }
}
