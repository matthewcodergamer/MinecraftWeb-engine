package mcport.rt;

import java.util.function.Predicate;
import net.minecraft.SystemReport;
import org.teavm.jso.JSBody;

/**
 * Browser data for net.minecraft.SystemReport. The original queries JMX MemoryMXBeans and
 * OSHI (native hardware probing); these report the equivalent facts the browser exposes.
 */
public final class SystemReportBrowser {
    private SystemReportBrowser() {
    }

    /**
     * Minecraft.fillSystemReport: system diagnostics must never prevent the browser client
     * from starting. The vanilla suppliers probe JVM management beans, OSHI and native
     * platform details; those APIs are not present in TeaVM/WebAssembly.
     */
    public static void fillSystemReport(SystemReport report) {
        report.setDetail("Operating System", "Browser");
        report.setDetail("Runtime", "TeaVM WebAssembly GC");
        report.setDetail("Graphics", "WebGPU");
        report.setDetail("Processor Logical Cores", String.valueOf(logicalCores()));
        report.setDetail("Device Memory (GB)", deviceMemory());
        report.setDetail("Platform", platform());
        report.setDetail("User Agent", userAgent());
        report.setDetail("Java VM", "Not available in browser");
    }

    /** lambda$new$3: "Memory (heap)". */
    public static Object heap() {
        return describeHeap();
    }

    /** lambda$new$4: "Memory (non-heap)" - no separate non-heap pool exists in a JS engine. */
    public static Object nonHeap() {
        return "n/a (browser JavaScript engine)";
    }

    /** lambda$new$6: "hardware". */
    public static void hardware(SystemReport report) {
        report.setDetail("Processor Logical Cores", String.valueOf(logicalCores()));
        report.setDetail("Device Memory (GB, navigator.deviceMemory)", deviceMemory());
        report.setDetail("Platform", platform());
    }

    /** lambda$new$7: "software". */
    public static void software(SystemReport report) {
        report.setDetail("User Agent", userAgent());
    }

    /** printJvmFlags: a browser has no JVM command-line flags. */
    public static String jvmFlags(Predicate<String> filter) {
        return "";
    }

    @JSBody(script = "var m = performance && performance.memory; return m ? "
            + "('used ' + Math.round(m.usedJSHeapSize/1048576) + ' MiB, total ' + Math.round(m.totalJSHeapSize/1048576)"
            + " + ' MiB, limit ' + Math.round(m.jsHeapSizeLimit/1048576) + ' MiB') : 'unavailable';")
    private static native String describeHeap();

    @JSBody(script = "return navigator.hardwareConcurrency || 1;")
    private static native int logicalCores();

    @JSBody(script = "return String(navigator.deviceMemory || 'unknown');")
    private static native String deviceMemory();

    @JSBody(script = "return String(navigator.platform || 'unknown');")
    private static native String platform();

    @JSBody(script = "return navigator.userAgent;")
    private static native String userAgent();
}
