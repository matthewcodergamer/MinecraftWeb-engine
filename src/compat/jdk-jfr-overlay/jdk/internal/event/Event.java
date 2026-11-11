package jdk.internal.event;
/** Minimal inert JFR event base for the browser port. */
public abstract class Event {
    protected Event() {}
    public void begin() {}
    public void end() {}
    public void commit() {}
    public boolean isEnabled() { return false; }
    public boolean shouldCommit() { return false; }
    public void set(int index, Object value) {}
}
