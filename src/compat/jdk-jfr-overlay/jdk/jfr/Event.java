package jdk.jfr;
/** Minimal JFR Event API; Flight Recorder is unavailable in browsers. */
public abstract class Event extends jdk.internal.event.Event {
    protected Event() { super(); }
    @Override public final void begin() {}
    @Override public final void end() {}
    @Override public final void commit() {}
    @Override public final boolean isEnabled() { return false; }
    @Override public final boolean shouldCommit() { return false; }
    @Override public final void set(int index, Object value) {}
}
