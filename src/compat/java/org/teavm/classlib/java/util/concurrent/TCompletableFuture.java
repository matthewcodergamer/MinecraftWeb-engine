package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * java.util.concurrent.CompletableFuture for TeaVM green threads, following JDK semantics:
 * dependent stages see exceptions wrapped in CompletionException, get() unwraps to
 * ExecutionException, join() throws CompletionException, *Async uses the common pool.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class TCompletableFuture<T> implements Future<T>, CompletionStage<T> {
    private static final class AltResult {
        final Throwable ex;

        AltResult(Throwable ex) {
            this.ex = ex;
        }
    }

    private static final AltResult NIL = new AltResult(null);

    private Object result;
    private List<Runnable> listeners;

    public TCompletableFuture() {
    }

    private static TCompletableFuture<Object> cast(CompletableFuture<?> f) {
        return (TCompletableFuture<Object>) (Object) f;
    }

    private static <U> CompletableFuture<U> wrap(TCompletableFuture<U> f) {
        return (CompletableFuture<U>) (Object) f;
    }

    static Executor asyncPool() {
        return ForkJoinPool.commonPool();
    }

    // ---------------------------------------------------------------- core

    private boolean completeWith(Object r) {
        List<Runnable> toRun;
        synchronized (this) {
            if (result != null) {
                return false;
            }
            result = r;
            toRun = listeners;
            listeners = null;
            notifyAll();
        }
        if (toRun != null) {
            for (Runnable l : toRun) {
                l.run();
            }
        }
        return true;
    }

    private void onDone(Runnable r) {
        synchronized (this) {
            if (result == null) {
                if (listeners == null) {
                    listeners = new ArrayList<>();
                }
                listeners.add(r);
                return;
            }
        }
        r.run();
    }

    private static Throwable wrapEx(Throwable ex) {
        return ex instanceof CompletionException ? ex : new CompletionException(ex);
    }

    private static Throwable unwrapEx(Throwable ex) {
        return ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
    }

    private Throwable exOf(Object r) {
        return r instanceof AltResult ? ((AltResult) r).ex : null;
    }

    private T valueOf(Object r) {
        return r instanceof AltResult ? null : (T) r;
    }

    private synchronized Object snapshot() {
        return result;
    }

    boolean completeValueInternal(Object v) {
        return completeWith(v == null ? NIL : v);
    }

    boolean completeThrowableInternal(Throwable ex) {
        return completeWith(new AltResult(wrapEx(ex)));
    }

    private static void run(Executor e, Runnable r) {
        if (e == null) {
            r.run();
        } else {
            e.execute(r);
        }
    }

    // ---------------------------------------------------------------- factories

    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier) {
        return supplyAsync(supplier, asyncPool());
    }

    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier, Executor executor) {
        TCompletableFuture<U> f = new TCompletableFuture<>();
        executor.execute(() -> {
            try {
                f.completeValueInternal(supplier.get());
            } catch (Throwable t) {
                f.completeThrowableInternal(t);
            }
        });
        return wrap(f);
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return runAsync(runnable, asyncPool());
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
        return supplyAsync(() -> {
            runnable.run();
            return null;
        }, executor);
    }

    public static <U> CompletableFuture<U> completedFuture(U value) {
        TCompletableFuture<U> f = new TCompletableFuture<>();
        f.completeValueInternal(value);
        return wrap(f);
    }

    public static <U> CompletionStage<U> completedStage(U value) {
        return completedFuture(value);
    }

    public static <U> CompletableFuture<U> failedFuture(Throwable ex) {
        TCompletableFuture<U> f = new TCompletableFuture<>();
        f.completeWith(new AltResult(ex));
        return wrap(f);
    }

    public static <U> CompletionStage<U> failedStage(Throwable ex) {
        return failedFuture(ex);
    }

    public static CompletableFuture<Void> allOf(CompletableFuture<?>... cfs) {
        TCompletableFuture<Void> all = new TCompletableFuture<>();
        if (cfs.length == 0) {
            all.completeValueInternal(null);
            return wrap(all);
        }
        int[] remaining = {cfs.length};
        for (CompletableFuture<?> c : cfs) {
            TCompletableFuture<Object> tc = cast(c);
            tc.onDone(() -> {
                boolean last;
                synchronized (remaining) {
                    last = --remaining[0] == 0;
                }
                if (last) {
                    Throwable firstEx = null;
                    for (CompletableFuture<?> d : cfs) {
                        Object r = cast(d).snapshot();
                        if (r instanceof AltResult && ((AltResult) r).ex != null) {
                            firstEx = ((AltResult) r).ex;
                            break;
                        }
                    }
                    if (firstEx != null) {
                        all.completeThrowableInternal(firstEx);
                    } else {
                        all.completeValueInternal(null);
                    }
                }
            });
        }
        return wrap(all);
    }

    public static CompletableFuture<Object> anyOf(CompletableFuture<?>... cfs) {
        TCompletableFuture<Object> any = new TCompletableFuture<>();
        for (CompletableFuture<?> c : cfs) {
            TCompletableFuture<Object> tc = cast(c);
            tc.onDone(() -> {
                Object r = tc.snapshot();
                if (r instanceof AltResult && ((AltResult) r).ex != null) {
                    any.completeThrowableInternal(((AltResult) r).ex);
                } else {
                    any.completeValueInternal(tc.valueOf(r));
                }
            });
        }
        return wrap(any);
    }

    // ---------------------------------------------------------------- Future

    @Override
    public boolean isDone() {
        return snapshot() != null;
    }

    @Override
    public boolean isCancelled() {
        Object r = snapshot();
        return r instanceof AltResult && ((AltResult) r).ex instanceof CancellationException;
    }

    public boolean isCompletedExceptionally() {
        Object r = snapshot();
        return r instanceof AltResult && r != NIL;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean cancelled = completeWith(new AltResult(new CancellationException()));
        return cancelled || isCancelled();
    }

    private synchronized Object waitFor() throws InterruptedException {
        while (result == null) {
            wait();
        }
        return result;
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        return reportGet(waitFor());
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        Object r;
        synchronized (this) {
            while (result == null) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    throw new TimeoutException();
                }
                wait(left);
            }
            r = result;
        }
        return reportGet(r);
    }

    private T reportGet(Object r) throws ExecutionException {
        Throwable ex = exOf(r);
        if (ex == null) {
            return valueOf(r);
        }
        if (ex instanceof CancellationException) {
            throw (CancellationException) ex;
        }
        throw new ExecutionException(unwrapEx(ex));
    }

    public T join() {
        Object r;
        boolean interrupted = false;
        while (true) {
            try {
                r = waitFor();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return reportJoin(r);
    }

    private T reportJoin(Object r) {
        Throwable ex = exOf(r);
        if (ex == null) {
            return valueOf(r);
        }
        if (ex instanceof CancellationException) {
            throw (CancellationException) ex;
        }
        throw (CompletionException) wrapEx(ex);
    }

    public T getNow(T valueIfAbsent) {
        Object r = snapshot();
        return r == null ? valueIfAbsent : reportJoin(r);
    }

    @Override
    public T resultNow() {
        Object r = snapshot();
        if (r == null || exOf(r) != null) {
            throw new IllegalStateException();
        }
        return valueOf(r);
    }

    @Override
    public Throwable exceptionNow() {
        Object r = snapshot();
        if (r == null || exOf(r) == null) {
            throw new IllegalStateException();
        }
        return unwrapEx(exOf(r));
    }

    public boolean complete(T value) {
        return completeValueInternal(value);
    }

    public boolean completeExceptionally(Throwable ex) {
        if (ex == null) {
            throw new NullPointerException();
        }
        return completeWith(new AltResult(ex));
    }

    public void obtrudeValue(T value) {
        synchronized (this) {
            result = value == null ? NIL : value;
            notifyAll();
        }
    }

    public void obtrudeException(Throwable ex) {
        synchronized (this) {
            result = new AltResult(ex);
            notifyAll();
        }
    }

    public int getNumberOfDependents() {
        synchronized (this) {
            return listeners == null ? 0 : listeners.size();
        }
    }

    public <U> CompletableFuture<U> newIncompleteFuture() {
        return wrap(new TCompletableFuture<>());
    }

    public Executor defaultExecutor() {
        return asyncPool();
    }

    public CompletableFuture<T> copy() {
        return thenApply(x -> x);
    }

    public CompletionStage<T> minimalCompletionStage() {
        return copy();
    }

    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
        executor.execute(() -> {
            try {
                completeValueInternal(supplier.get());
            } catch (Throwable t) {
                completeThrowableInternal(t);
            }
        });
        return wrap(this);
    }

    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
        return completeAsync(supplier, asyncPool());
    }

    public CompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
        long millis = unit.toMillis(timeout);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ignored) {
                return;
            }
            completeWith(new AltResult(new TimeoutException()));
        });
        t.setDaemon(true);
        t.start();
        return wrap(this);
    }

    public CompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
        long millis = unit.toMillis(timeout);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ignored) {
                return;
            }
            completeValueInternal(value);
        });
        t.setDaemon(true);
        t.start();
        return wrap(this);
    }

    @Override
    public CompletableFuture<T> toCompletableFuture() {
        return wrap(this);
    }

    // ---------------------------------------------------------------- dependent stages

    /** Runs fn(value, exception) when this completes (on executor if non-null); fn completes dst. */
    private <U> CompletableFuture<U> chain(Executor e, BiConsumer<Object, TCompletableFuture<U>> body) {
        TCompletableFuture<U> dst = new TCompletableFuture<>();
        onDone(() -> {
            Object r = snapshot();
            run(e, () -> {
                try {
                    body.accept(r, dst);
                } catch (Throwable t) {
                    dst.completeThrowableInternal(t);
                }
            });
        });
        return wrap(dst);
    }

    private <U> CompletableFuture<U> uniApply(Executor e, Function<? super T, ? extends U> fn) {
        return chain(e, (r, dst) -> {
            Throwable ex = exOf(r);
            if (ex != null) {
                dst.completeThrowableInternal(ex);
            } else {
                dst.completeValueInternal(fn.apply(valueOf(r)));
            }
        });
    }

    @Override
    public <U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> fn) {
        return uniApply(null, fn);
    }

    @Override
    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
        return uniApply(asyncPool(), fn);
    }

    @Override
    public <U> CompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
        return uniApply(executor, fn);
    }

    @Override
    public CompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        return uniApply(null, v -> {
            action.accept(v);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
        return thenAcceptAsync(action, asyncPool());
    }

    @Override
    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return uniApply(executor, v -> {
            action.accept(v);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> thenRun(Runnable action) {
        return uniApply(null, v -> {
            action.run();
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> thenRunAsync(Runnable action) {
        return thenRunAsync(action, asyncPool());
    }

    @Override
    public CompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
        return uniApply(executor, v -> {
            action.run();
            return null;
        });
    }

    private <U, V> CompletableFuture<V> biApply(Executor e, CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn) {
        TCompletableFuture<Object> o = cast(other.toCompletableFuture());
        return chain(null, (r, dst) -> o.onDone(() -> {
            Object r2 = o.snapshot();
            run(e, () -> {
                try {
                    Throwable ex = exOf(r) != null ? exOf(r) : o.exOf(r2);
                    if (ex != null) {
                        dst.completeThrowableInternal(ex);
                    } else {
                        dst.completeValueInternal(fn.apply(valueOf(r), (U) o.valueOf(r2)));
                    }
                } catch (Throwable t) {
                    dst.completeThrowableInternal(t);
                }
            });
        }));
    }

    @Override
    public <U, V> CompletableFuture<V> thenCombine(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn) {
        return biApply(null, other, fn);
    }

    @Override
    public <U, V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn) {
        return biApply(asyncPool(), other, fn);
    }

    @Override
    public <U, V> CompletableFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) {
        return biApply(executor, other, fn);
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action) {
        return biApply(null, other, (a, b) -> {
            action.accept(a, b);
            return null;
        });
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action) {
        return thenAcceptBothAsync(other, action, asyncPool());
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action, Executor executor) {
        return biApply(executor, other, (a, b) -> {
            action.accept(a, b);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        return biApply(null, other, (a, b) -> {
            action.run();
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
        return runAfterBothAsync(other, action, asyncPool());
    }

    @Override
    public CompletableFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action,
            Executor executor) {
        return biApply(executor, other, (a, b) -> {
            action.run();
            return null;
        });
    }

    private <U> CompletableFuture<U> orApply(Executor e, CompletionStage<? extends T> other,
            Function<? super T, U> fn) {
        TCompletableFuture<Object> o = cast(other.toCompletableFuture());
        TCompletableFuture<U> dst = new TCompletableFuture<>();
        Runnable[] fired = {null};
        for (TCompletableFuture<?> src : new TCompletableFuture<?>[] {this, o}) {
            TCompletableFuture<Object> s = (TCompletableFuture<Object>) src;
            s.onDone(() -> {
                synchronized (fired) {
                    if (fired[0] != null) {
                        return;
                    }
                    fired[0] = () -> { };
                }
                Object r = s.snapshot();
                run(e, () -> {
                    try {
                        Throwable ex = s.exOf(r);
                        if (ex != null) {
                            dst.completeThrowableInternal(ex);
                        } else {
                            dst.completeValueInternal(fn.apply((T) s.valueOf(r)));
                        }
                    } catch (Throwable t) {
                        dst.completeThrowableInternal(t);
                    }
                });
            });
        }
        return wrap(dst);
    }

    @Override
    public <U> CompletableFuture<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return orApply(null, other, fn);
    }

    @Override
    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other,
            Function<? super T, U> fn) {
        return orApply(asyncPool(), other, fn);
    }

    @Override
    public <U> CompletableFuture<U> applyToEitherAsync(CompletionStage<? extends T> other,
            Function<? super T, U> fn, Executor executor) {
        return orApply(executor, other, fn);
    }

    @Override
    public CompletableFuture<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return orApply(null, other, v -> {
            action.accept(v);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other,
            Consumer<? super T> action) {
        return acceptEitherAsync(other, action, asyncPool());
    }

    @Override
    public CompletableFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other,
            Consumer<? super T> action, Executor executor) {
        return orApply(executor, other, v -> {
            action.accept(v);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        return orApply(null, (CompletionStage<T>) other, v -> {
            action.run();
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
        return runAfterEitherAsync(other, action, asyncPool());
    }

    @Override
    public CompletableFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action,
            Executor executor) {
        return orApply(executor, (CompletionStage<T>) other, v -> {
            action.run();
            return null;
        });
    }

    private <U> CompletableFuture<U> uniCompose(Executor e, Function<? super T, ? extends CompletionStage<U>> fn) {
        return chain(e, (r, dst) -> {
            Throwable ex = exOf(r);
            if (ex != null) {
                dst.completeThrowableInternal(ex);
                return;
            }
            TCompletableFuture<Object> inner = cast(fn.apply(valueOf(r)).toCompletableFuture());
            inner.onDone(() -> {
                Object r2 = inner.snapshot();
                Throwable ex2 = inner.exOf(r2);
                if (ex2 != null) {
                    dst.completeThrowableInternal(ex2);
                } else {
                    dst.completeValueInternal(inner.valueOf(r2));
                }
            });
        });
    }

    @Override
    public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        return uniCompose(null, fn);
    }

    @Override
    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
        return uniCompose(asyncPool(), fn);
    }

    @Override
    public <U> CompletableFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn,
            Executor executor) {
        return uniCompose(executor, fn);
    }

    private CompletableFuture<T> uniWhen(Executor e, BiConsumer<? super T, ? super Throwable> action) {
        return chain(e, (r, dst) -> {
            Throwable ex = exOf(r);
            try {
                action.accept(valueOf(r), ex);
            } catch (Throwable t) {
                if (ex == null) {
                    dst.completeThrowableInternal(t);
                    return;
                }
                if (ex != t) {
                    ex.addSuppressed(t);
                }
            }
            if (ex != null) {
                dst.completeThrowableInternal(ex);
            } else {
                dst.completeValueInternal(valueOf(r));
            }
        });
    }

    @Override
    public CompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return uniWhen(null, action);
    }

    @Override
    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return uniWhen(asyncPool(), action);
    }

    @Override
    public CompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action,
            Executor executor) {
        return uniWhen(executor, action);
    }

    private <U> CompletableFuture<U> uniHandle(Executor e, BiFunction<? super T, Throwable, ? extends U> fn) {
        return chain(e, (r, dst) -> dst.completeValueInternal(fn.apply(valueOf(r), exOf(r))));
    }

    @Override
    public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
        return uniHandle(null, fn);
    }

    @Override
    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
        return uniHandle(asyncPool(), fn);
    }

    @Override
    public <U> CompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn,
            Executor executor) {
        return uniHandle(executor, fn);
    }

    private CompletableFuture<T> uniExceptionally(Executor e, Function<Throwable, ? extends T> fn) {
        return chain(e, (r, dst) -> {
            Throwable ex = exOf(r);
            if (ex == null) {
                dst.completeValueInternal(valueOf(r));
            } else {
                dst.completeValueInternal(fn.apply(ex));
            }
        });
    }

    @Override
    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
        return uniExceptionally(null, fn);
    }

    @Override
    public CompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn) {
        return uniExceptionally(asyncPool(), fn);
    }

    @Override
    public CompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn, Executor executor) {
        return uniExceptionally(executor, fn);
    }

    private CompletableFuture<T> uniExceptionallyCompose(Executor e,
            Function<Throwable, ? extends CompletionStage<T>> fn) {
        return chain(e, (r, dst) -> {
            Throwable ex = exOf(r);
            if (ex == null) {
                dst.completeValueInternal(valueOf(r));
                return;
            }
            TCompletableFuture<Object> inner = cast(fn.apply(ex).toCompletableFuture());
            inner.onDone(() -> {
                Object r2 = inner.snapshot();
                Throwable ex2 = inner.exOf(r2);
                if (ex2 != null) {
                    dst.completeThrowableInternal(ex2);
                } else {
                    dst.completeValueInternal(inner.valueOf(r2));
                }
            });
        });
    }

    @Override
    public CompletableFuture<T> exceptionallyCompose(Function<Throwable, ? extends CompletionStage<T>> fn) {
        return uniExceptionallyCompose(null, fn);
    }

    @Override
    public CompletableFuture<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> fn) {
        return uniExceptionallyCompose(asyncPool(), fn);
    }

    @Override
    public CompletableFuture<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> fn,
            Executor executor) {
        return uniExceptionallyCompose(executor, fn);
    }

    @Override
    public String toString() {
        Object r = snapshot();
        String status = r == null ? "[Incomplete]"
                : exOf(r) != null ? "[Completed exceptionally: " + exOf(r) + "]" : "[Completed normally]";
        return super.toString() + status;
    }
}
