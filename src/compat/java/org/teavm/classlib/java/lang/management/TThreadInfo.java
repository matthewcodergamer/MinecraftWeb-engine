package org.teavm.classlib.java.lang.management;

/**
 * java.lang.management.ThreadInfo for the browser runtime.
 *
 * <p>Only reachable from the dedicated-server watchdog/debug writers, which are not
 * part of the browser client, but the type must exist so the ThreadMXBean signature
 * links.</p>
 */
public class TThreadInfo {
    private final String threadName;
    private final long threadId;
    private final Thread.State threadState;
    private final boolean daemon;
    private final StackTraceElement[] stackTrace;

    public TThreadInfo(String threadName, long threadId, Thread.State threadState, boolean daemon,
            StackTraceElement[] stackTrace) {
        this.threadName = threadName;
        this.threadId = threadId;
        this.threadState = threadState;
        this.daemon = daemon;
        this.stackTrace = stackTrace == null ? new StackTraceElement[0] : stackTrace;
    }

    public String getThreadName() {
        return threadName;
    }

    public long getThreadId() {
        return threadId;
    }

    public Thread.State getThreadState() {
        return threadState;
    }

    public boolean isDaemon() {
        return daemon;
    }

    public StackTraceElement[] getStackTrace() {
        return stackTrace;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(threadName).append('"');
        if (daemon) {
            sb.append(" daemon");
        }
        sb.append(" Id=").append(threadId).append(' ').append(threadState);
        for (StackTraceElement element : stackTrace) {
            sb.append("\n\tat ").append(element);
        }
        return sb.toString();
    }
}
