package dev.flint.networkchat;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SessionTest {
    @Test void cooldownIsAtomicAcrossConcurrentMessages() throws Exception {
        Session state = new Session(); AtomicInteger accepted = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < 100; i++) executor.submit(() -> { try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (state.allow("global", 100, 50)) accepted.incrementAndGet(); });
            start.countDown(); executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        assertEquals(1, accepted.get()); assertTrue(state.allow("global", 150, 50));
        assertTrue(state.allow("staff", 100, 50));
    }
    @Test void ignoresAreBoundedAndToggleable() {
        Session state = new Session(); UUID first = UUID.randomUUID();
        assertTrue(state.toggleIgnore(first));
        for (int i = 1; i < 1000; i++) assertTrue(state.toggleIgnore(UUID.randomUUID()));
        assertNull(state.toggleIgnore(UUID.randomUUID())); assertEquals(1000, state.ignored.size());
        assertFalse(state.toggleIgnore(first)); assertTrue(state.toggleIgnore(UUID.randomUUID()));
    }
    @Test void messageQueueIsBoundedOrderedAndRecoversAfterFailure() {
        Session state = new Session(); CompletableFuture<Void> first = new CompletableFuture<>();
        java.util.List<Integer> order = new java.util.ArrayList<>();
        assertTrue(state.enqueue(() -> { order.add(0); return first; }));
        for (int i = 1; i < 64; i++) {
            int number = i;
            assertTrue(state.enqueue(() -> { order.add(number); return CompletableFuture.completedFuture(null); }));
        }
        assertFalse(state.enqueue(() -> { fail("Overflow executed"); return null; })); assertEquals(java.util.List.of(0), order);
        first.completeExceptionally(new IllegalStateException("backend failed"));
        assertEquals(64, order.size()); for (int i = 0; i < 64; i++) assertEquals(i, order.get(i));
        assertTrue(state.enqueue(() -> CompletableFuture.completedFuture(null)));
    }
}
