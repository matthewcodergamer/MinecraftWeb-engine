package mcport.rt;

/**
 * Runtime-visible monitor bookkeeping for the Wasm-GC browser build.
 *
 * The monitor implementation itself lives in the class library overlay
 * (java.lang.Object). Static state declared there is not reliably visible to
 * callers outside the class library in the TeaVM Wasm-GC output: a probe that
 * read the overlay's own counters from the entry class saw zeros while the
 * monitor code itself saw live values. Every value the boot watchdog needs is
 * therefore copied into this ordinary class as it changes.
 */
public final class MonitorRegistry {
    private MonitorRegistry() {
    }

    private static final int TRACE_LIMIT = 4000;

    private static Entry[] entries;
    private static int entryCount;
    private static int traceTotal;
    private static int traceId;
    private static int diagCount;
    private static String lastDiag;

    // Path counters: which monitor entry point the client actually executes.
    // Needed because the class library can be reached through several lowered
    // forms (sync enter, async enter, async wait re-acquire).
    private static int enterCalls;
    private static int syncEnterCalls;
    private static int asyncEnterCalls;
    private static int asyncWaitCalls;
    private static int immediateAcquires;
    private static int grantCalls;
    private static int exitCalls;
    private static int adoptedCalls;
    private static int platformSkips;
    private static boolean gcPlatform;

    /** One lock we have seen; all fields are plain copies of monitor state. */
    public static final class Entry {
        public int traceId;
        public String objectClass;
        public String ownerName;
        public int ownerFiber = -1;
        public int count;
        public int waiters;
        public boolean ownerAlive;
        public boolean owned;
    }

    public static void enterCall() {
        enterCalls++;
    }

    public static void syncEnterCall() {
        syncEnterCalls++;
    }

    public static void asyncEnterCall() {
        asyncEnterCalls++;
    }

    public static void asyncWaitCall() {
        asyncWaitCalls++;
    }

    public static void immediateAcquire() {
        immediateAcquires++;
    }

    public static void grantCall() {
        grantCalls++;
    }

    public static void exitCall() {
        exitCalls++;
    }

    public static void adoptedCall() {
        adoptedCalls++;
    }

    /** Records the platform detector's answer from inside the class library. */
    public static void platform(boolean webAssemblyGC) {
        gcPlatform = webAssemblyGC;
        if (!webAssemblyGC) {
            platformSkips++;
        }
    }

    public static int nextTraceId() {
        return ++traceId;
    }

    public static Entry register(int lockTraceId, String objectClass) {
        Entry entry = new Entry();
        entry.traceId = lockTraceId;
        entry.objectClass = objectClass;
        if (entries == null) {
            entries = new Entry[512];
        } else if (entryCount == entries.length) {
            Entry[] expanded = new Entry[entries.length * 2];
            System.arraycopy(entries, 0, expanded, 0, entries.length);
            entries = expanded;
        }
        entries[entryCount++] = entry;
        return entry;
    }

    /** Records the current owner of a lock. */
    public static void owner(Entry entry, String ownerName, int fiberId, int count, int waiters) {
        if (entry == null) {
            return;
        }
        entry.owned = ownerName != null && !"null".equals(ownerName);
        entry.ownerName = ownerName;
        entry.ownerFiber = fiberId;
        entry.ownerAlive = true;
        entry.count = count;
        entry.waiters = waiters;
    }

    /** Records that the lock has no owner. */
    public static void released(Entry entry, int waiters) {
        if (entry == null) {
            return;
        }
        entry.owned = false;
        entry.ownerName = null;
        entry.ownerFiber = -1;
        entry.ownerAlive = false;
        entry.count = 0;
        entry.waiters = waiters;
    }

    public static int currentFiberId() {
        try {
            return org.teavm.runtime.Fiber.currentId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static void trace(String message) {
        if (traceTotal >= TRACE_LIMIT) {
            return;
        }
        traceTotal++;
        try {
            BrowserRuntime.report("monitor", message);
        } catch (Throwable ignored) {
        }
    }

    public static void diag(String message) {
        int n = ++diagCount;
        if (n > 20000 || (n > 20 && n % 500 != 0)) {
            return;
        }
        lastDiag = "#" + n + " " + message;
        try {
            BrowserRuntime.log("[TOBJ] entries=" + entryCount + " traced=" + traceTotal + " " + lastDiag);
        } catch (Throwable ignored) {
        }
    }

    /**
     * One summary line plus one segment per owned or contended lock. The owner's
     * fiber id matches {@code Fiber.dump()}, so the watchdog's two reports can be
     * cross-referenced to see where the lock owner is suspended.
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("locks=").append(entryCount).append(" traced=").append(traceTotal)
                .append(" enter=").append(enterCalls).append(" sync=").append(syncEnterCalls)
                .append(" async=").append(asyncEnterCalls).append(" rewait=").append(asyncWaitCalls)
                .append(" imm=").append(immediateAcquires).append(" grant=").append(grantCalls)
                .append(" exit=").append(exitCalls).append(" adopt=").append(adoptedCalls)
                .append(" gc=").append(gcPlatform).append(" gcSkip=").append(platformSkips)
                .append(" last=").append(lastDiag);
        Entry[] snapshot = entries;
        int count = entryCount;
        for (int i = 0; i < count && snapshot != null && i < snapshot.length; ++i) {
            Entry e = snapshot[i];
            if (e == null || (!e.owned && e.waiters == 0)) {
                continue;
            }
            sb.append(" | m").append(e.traceId)
                    .append(" obj=").append(e.objectClass != null ? e.objectClass : "?")
                    .append(" owner=").append(e.ownerName != null ? e.ownerName : "null")
                    .append(" count=").append(e.count)
                    .append(" waiters=").append(e.waiters);
            if (e.owned) {
                sb.append(" ownerFiber=#").append(e.ownerFiber);
                sb.append(" ownerAlive=").append(e.ownerAlive);
            }
        }
        return sb.toString();
    }
}
