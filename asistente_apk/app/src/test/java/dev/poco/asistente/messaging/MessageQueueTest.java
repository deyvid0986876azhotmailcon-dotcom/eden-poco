package dev.poco.asistente.messaging;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class MessageQueueTest {
    @Test public void drainsOnceInOrderAndSurvivesAFailedJob() {
        ArrayDeque<Runnable> scheduled = new ArrayDeque<>(); List<Integer> results = new ArrayList<>();
        MessageQueue queue = new MessageQueue(scheduled::add);
        queue.add(() -> results.add(1)); queue.add(() -> { throw new IllegalStateException(); }); queue.add(() -> results.add(2));
        assertEquals(1, scheduled.size()); scheduled.remove().run(); assertEquals(Arrays.asList(1, 2), results);
        queue.add(() -> results.add(3)); scheduled.remove().run(); assertEquals(Arrays.asList(1, 2, 3), results);
    }
    @Test public void stopClearsQueuedJobsAndAllowsNewMessages() {
        ArrayDeque<Runnable> scheduled = new ArrayDeque<>(); List<Integer> results = new ArrayList<>();
        MessageQueue queue = new MessageQueue(scheduled::add);
        queue.add(() -> results.add(1)); queue.add(() -> results.add(2)); assertEquals(2, queue.clear());
        queue.add(() -> results.add(3)); assertEquals(1, scheduled.size()); scheduled.remove().run(); assertEquals(Arrays.asList(3), results);
    }
    @Test public void blockedJobDoesNotBlockStopOrStartASecondDrain() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(); MessageQueue queue = new MessageQueue(executor);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        List<Integer> results = new ArrayList<>();
        try {
            queue.add(() -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } results.add(1); });
            assertTrue(entered.await(5, TimeUnit.SECONDS)); queue.add(() -> results.add(2)); assertEquals(1, queue.clear());
            queue.add(() -> { results.add(3); done.countDown(); }); assertEquals(1, done.getCount());
            release.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(Arrays.asList(1, 3), results);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
