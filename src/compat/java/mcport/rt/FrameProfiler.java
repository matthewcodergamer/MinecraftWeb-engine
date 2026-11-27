package mcport.rt;

import org.teavm.jso.JSBody;

/**
 * Coarse per-frame phase timers for the browser port.
 *
 * <p>{@code __mcFrames.workAvg} already says how long the whole Java frame
 * takes. These counters split that number into the port-owned costs (queue
 * submit, frame hooks, completed readbacks) so a soak can distinguish a slow
 * renderer from a slow game tick without a full profiler. Reporting is
 * throttled to once a second, so the timer itself is not part of the frame
 * budget.</p>
 */
public final class FrameProfiler {
    private static long submitNs;
    private static long hooksNs;
    private static long readbackNs;
    private static int frames;
    private static long lastReportNs = System.nanoTime();

    private FrameProfiler() {
    }

    public static void submit(long ns) {
        submitNs += ns;
    }

    public static void hooks(long ns) {
        hooksNs += ns;
    }

    public static void readback(long ns) {
        readbackNs += ns;
    }

    /** Called once per presented frame, after readbacks have run. */
    public static void endFrame() {
        frames++;
        long now = System.nanoTime();
        if (frames < 60 && now - lastReportNs < 1_000_000_000L) {
            return;
        }
        double count = Math.max(1, frames);
        report(submitNs / 1.0e6 / count, hooksNs / 1.0e6 / count,
                readbackNs / 1.0e6 / count, frames);
        submitNs = 0L;
        hooksNs = 0L;
        readbackNs = 0L;
        frames = 0;
        lastReportNs = now;
    }

    @JSBody(params = {"submitMs", "hooksMs", "readbackMs", "frames"}, script = ""
            + "var f = window.__mcFrames || (window.__mcFrames = {});"
            + "f.phases = { submitMs: Math.round(submitMs * 100) / 100,"
            + " hooksMs: Math.round(hooksMs * 100) / 100,"
            + " readbackMs: Math.round(readbackMs * 100) / 100, frames: frames };")
    private static native void report(double submitMs, double hooksMs, double readbackMs, int frames);
}
