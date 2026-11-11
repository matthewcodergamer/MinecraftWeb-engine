package java.util.concurrent.locks;

import java.io.Serializable;

/**
 * Minimal Java-base compatibility definition used by the browser port.
 * Thread ownership is retained for synchronizer bookkeeping; no native
 * locking primitive is introduced here.
 */
public abstract class AbstractOwnableSynchronizer implements Serializable {
    private static final long serialVersionUID = 3737899427754241961L;
    private transient Thread exclusiveOwnerThread;

    protected AbstractOwnableSynchronizer() {}

    protected final void setExclusiveOwnerThread(Thread thread) {
        exclusiveOwnerThread = thread;
    }

    protected final Thread getExclusiveOwnerThread() {
        return exclusiveOwnerThread;
    }
}
