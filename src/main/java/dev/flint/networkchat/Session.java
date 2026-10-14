package dev.flint.networkchat;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Session-only preferences and bounded per-channel cooldown state; removed on disconnect. */
final class Session {
    volatile String channel;
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
    synchronized boolean toggleReceiving() { receiving = !receiving; return receiving; }
    synchronized Boolean toggleIgnore(UUID target) {
        if (ignored.remove(target)) return false;
        // Bound session memory even if thousands of distinct players visit the proxy.
        if (ignored.size() >= 1000) return null;
        ignored.add(target); return true;
    }
}
