package mcport.rt.patch;

import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;
import java.util.concurrent.TimeUnit;

/**
 * Browser-safe blocking waits for Netty promises.
 *
 * <p>TeaVM's Wasm-GC {@code Object.wait()} path strands a fiber when another
 * fiber completes the monitor (the same reason the classlib
 * {@code CompletableFuture.join()} polls with {@link Thread#sleep}). Netty's
 * {@link DefaultPromise#syncUninterruptibly()} waits on the promise monitor, and
 * Minecraft calls it on the render thread when it opens the integrated server's
 * memory channel ({@code ServerConnectionListener.startMemoryChannel()}) and
 * when the client connects to that channel. Polling {@code isDone()} keeps the
 * render fiber runnable so the local event loop can finish the bind/connect.
 */
public final class NettyPromisePatches {
    private NettyPromisePatches() {
    }

    private static long pollDelay(int emptyPolls) {
        if (emptyPolls < 50) {
            return 1L;
        }
        if (emptyPolls < 200) {
            return 2L;
        }
        return 5L;
    }

    public static Promise<?> await(DefaultPromise<?> promise) throws InterruptedException {
        while (!promise.isDone()) {
            Thread.sleep(pollDelay(0));
        }
        return promise;
    }

    public static Promise<?> awaitUninterruptibly(DefaultPromise<?> promise) {
        boolean interrupted = false;
        int emptyPolls = 0;
        while (!promise.isDone()) {
            try {
                Thread.sleep(pollDelay(emptyPolls++));
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return promise;
    }

    public static boolean await(DefaultPromise<?> promise, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        int emptyPolls = 0;
        while (!promise.isDone()) {
            long left = deadline - System.nanoTime();
            if (left <= 0L) {
                return false;
            }
            Thread.sleep(Math.max(1L, Math.min(pollDelay(emptyPolls++), left / 1_000_000L)));
        }
        return true;
    }

    public static boolean awaitUninterruptibly(DefaultPromise<?> promise, long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        boolean interrupted = false;
        int emptyPolls = 0;
        while (!promise.isDone()) {
            long left = deadline - System.nanoTime();
            if (left <= 0L) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
            try {
                Thread.sleep(Math.max(1L, Math.min(pollDelay(emptyPolls++), left / 1_000_000L)));
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    public static boolean awaitUninterruptibly(DefaultPromise<?> promise, long timeoutMillis) {
        return awaitUninterruptibly(promise, timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
