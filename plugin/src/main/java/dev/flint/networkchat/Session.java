package dev.flint.networkchat;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Session-only preferences and bounded per-channel cooldown state; removed on disconnect. */
final class Session {
    final com.velocitypowered.api.proxy.Player owner;
    Session() { this(null); }
    Session(com.velocitypowered.api.proxy.Player owner) { this.owner = owner; }
    volatile String channel;
    volatile boolean staffChat;
    volatile UUID reply;
    volatile boolean receiving = true;
    final Set<UUID> ignored = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> last = new HashMap<>();
    synchronized boolean allow(String channel, long now, long cooldownNanos) {
        Long previous = last.get(channel);
        if (previous != null && now - previous < cooldownNanos) return false;
        last.put(channel, now);
        return true;
    }
    private java.util.concurrent.CompletableFuture<Void> tail = java.util.concurrent.CompletableFuture.completedFuture(null);
    private int pending;
    boolean enqueue(java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> operation) {
        java.util.concurrent.CompletableFuture<Void> previous;
        java.util.concurrent.CompletableFuture<Void> gate = new java.util.concurrent.CompletableFuture<>();
        synchronized (this) {
            if (pending >= 64) return false;
            pending++;
            previous = tail;
            tail = gate;
        }
        // Never invoke a third-party resolver while holding the session lock.
        previous.handle((v, error) -> null).thenCompose(v -> operation.get()).whenComplete((v, error) -> {
            synchronized (this) { pending--; }
            gate.complete(null);
        });
        return true;
    }
    synchronized boolean toggleStaff(Boolean enabled) { staffChat = enabled == null ? !staffChat : enabled; return staffChat; }
    synchronized boolean toggleReceiving() { receiving = !receiving; return receiving; }
    synchronized Boolean toggleIgnore(UUID target) {
        if (ignored.remove(target)) return false;
        // Bound session memory even if thousands of distinct players visit the proxy.
        if (ignored.size() >= 1000) return null;
        ignored.add(target); return true;
    }
}
