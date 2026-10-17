package dev.flint.networkchat.probe;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** CI-only fixture, never included in the bridge release JAR. */
public final class InventoryFixture extends JavaPlugin {
    public boolean ready;
    public boolean isReady(UUID player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Readiness queried off main thread");
        return ready;
    }
}
