package dev.poco.asistente.messaging;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/** One drain at a time, also when PARAR arrives during a blocked HTTP call. */
public final class MessageQueue {
    private final Executor executor;
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private boolean running;
    public MessageQueue(Executor executor) { this.executor = executor; }
    public synchronized void add(Runnable task) {
        pending.add(task);
        if (!running) { running = true; executor.execute(this::drain); }
    }
    public synchronized int clear() { int size = pending.size(); pending.clear(); return size; }
    private void drain() {
        while (true) {
            Runnable next;
            synchronized (this) {
                next = pending.poll();
                if (next == null) { running = false; return; }
            }
            try { next.run(); }
            catch (RuntimeException ignored) { /* Each adapter reports failures; do not strand following jobs. */ }
        }
    }
}
