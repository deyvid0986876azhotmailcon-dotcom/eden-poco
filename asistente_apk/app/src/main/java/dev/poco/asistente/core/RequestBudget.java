package dev.poco.asistente.core;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One monotonic deadline shared by routing, Pro and fallbacks. Workers only perform reads. */
public final class RequestBudget {
    public static final long REQUEST_MS = 70_000, ROUTER_MS = 30_000, MIN_PRO_MS = 15_000;
    public static final long PRO_CHAT_MS = 60_000, PRO_SEARCH_MS = 45_000, CHECK_MS = 100;
    public static final String FALLBACK = "No pude responder ahora; probá en un rato.";
    private static final ExecutorService READS = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "bounded-read"); t.setDaemon(true); return t; });
    private final LongSupplier clock;
    private final long deadline;
    public RequestBudget(LongSupplier clock) { this.clock = clock; deadline = clock.getAsLong() + REQUEST_MS; }
    public long remaining() { return Math.max(0, deadline - clock.getAsLong()); }
    public boolean expired() { return remaining() == 0; }
    public boolean canUsePro() { return remaining() >= MIN_PRO_MS; }
    public <T> T read(Callable<T> read, BooleanSupplier cancelled) throws Exception {
        return read(read, cancelled, REQUEST_MS);
    }
    public <T> T read(Callable<T> read, BooleanSupplier cancelled, long maximum) throws Exception {
        long allowance = Math.min(remaining(), maximum);
        if (allowance <= 0) throw new TimeoutException();
        if (cancelled.getAsBoolean()) throw new IllegalArgumentException("Pedido cancelado.");
        long stageEnd = clock.getAsLong() + allowance;
        // Also bound wall time when a test clock stands still or a provider ignores interruption.
        long realEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(allowance);
        Future<T> task = READS.submit(read);
        try {
            for (;;) {
                if (cancelled.getAsBoolean()) throw new IllegalArgumentException("Pedido cancelado.");
                if (expired() || clock.getAsLong() >= stageEnd || System.nanoTime() >= realEnd) throw new TimeoutException();
                try {
                    T value = task.get(Math.min(CHECK_MS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(realEnd - System.nanoTime()))), TimeUnit.MILLISECONDS);
                    if (cancelled.getAsBoolean()) throw new IllegalArgumentException("Pedido cancelado.");
                    if (expired() || clock.getAsLong() >= stageEnd || System.nanoTime() >= realEnd) throw new TimeoutException();
                    return value;
                } catch (TimeoutException waiting) {
                    if (task.isDone()) throw waiting;
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    if (cause instanceof Error) throw (Error) cause;
                    throw failure;
                }
            }
        } finally { task.cancel(true); }
    }
}
