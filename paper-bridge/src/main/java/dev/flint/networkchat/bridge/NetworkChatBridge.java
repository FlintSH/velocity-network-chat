package dev.flint.networkchat.bridge;

import dev.flint.networkchat.teleport.TeleportProtocol;
import dev.flint.networkchat.teleport.TeleportProtocol.Packet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Authenticated Paper companion for the Velocity Network Chat teleport commands. */
public final class NetworkChatBridge extends JavaPlugin implements PluginMessageListener {
    private String secret;
    private TeleportService service;
    private volatile boolean active;

    @Override public void onEnable() {
        saveDefaultConfig();
        secret = getConfig().getString("shared-secret", "");
        try {
            TeleportProtocol.validateSecret(secret);
        } catch (IllegalArgumentException ignored) {
            getLogger().severe("Set shared-secret to the same random secret as Velocity (at least 32 characters). Bridge disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Set<String> allowed = Set.copyOf(getConfig().getStringList("allowed-worlds"));
        Set<String> blocked = Set.copyOf(getConfig().getStringList("blocked-worlds"));
        InventoryReadiness inventory = new InventoryReadiness(
                () -> getServer().getPluginManager().getPlugin("NetworkInventorySync"), Bukkit::isPrimaryThread,
                getConfig().getBoolean("require-inventory-sync", false));
        int capacity = Math.clamp(getConfig().getInt("replay-cache-size", 16384), 256, 65536);
        service = new TeleportService(new PaperPlatform(allowed, blocked, inventory), capacity);
        getServer().getMessenger().registerOutgoingPluginChannel(this, TeleportProtocol.CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, TeleportProtocol.CHANNEL, this);
        active = true;
        getLogger().info("Authenticated teleport bridge enabled for Paper/Purpur 26.1.2.");
    }

    @Override public void onDisable() {
        active = false;
        if (service != null) service.clear();
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getScheduler().cancelTasks(this);
        secret = null;
    }

    @Override public void onPluginMessageReceived(String channel, Player carrier, byte[] bytes) {
        if (!active || !TeleportProtocol.CHANNEL.equals(channel) || !Bukkit.isPrimaryThread()) return;
        Packet request;
        try {
            request = TeleportProtocol.decode(bytes, secret, System.currentTimeMillis());
        } catch (IllegalArgumentException ignored) {
            // Client packets are untrusted. Invalid authentication must not flood logs or trigger a reply.
            return;
        }
        service.receive(request, response -> {
            if (active && carrier.isOnline() && getServer().getPlayer(carrier.getUniqueId()) == carrier)
                carrier.sendPluginMessage(this, TeleportProtocol.CHANNEL, TeleportProtocol.encode(response, secret));
        });
    }

    private final class PaperPlatform implements TeleportService.Platform {
        private final Set<String> allowed, blocked;
        private final InventoryReadiness inventory;
        PaperPlatform(Set<String> allowed, Set<String> blocked, InventoryReadiness inventory) {
            this.allowed = allowed;
            this.blocked = blocked;
            this.inventory = inventory;
        }
        @Override public long now() { return System.currentTimeMillis(); }
        @Override public boolean active() { return active; }
        @Override public TeleportService.Session player(UUID id) {
            Player player = getServer().getPlayer(id);
            return player == null ? null : new TeleportService.Session(id, player, player.isOnline(),
                    !player.isDead() && player.isValid(), player.getWorld().getName());
        }
        @Override public TeleportService.Readiness readiness(UUID id) { return inventory.check(id); }
        @Override public boolean allowed(String world) {
            return !blocked.contains(world) && (allowed.isEmpty() || allowed.contains(world));
        }
        @Override public TeleportService.Destination location(TeleportService.Session player) {
            Location at = ((Player) player.identity()).getLocation();
            return new TeleportService.Destination(at.getWorld().getUID(), at.getWorld().getName(),
                    at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        }
        @Override public CompletionStage<Void> load(TeleportService.Destination destination) {
            World world = getServer().getWorld(destination.worldId());
            if (world == null) return CompletableFuture.failedFuture(new IllegalStateException("World unavailable"));
            // Resolve the chunk asynchronously, then revalidate immediately before synchronous teleport.
            // teleportAsync alone cannot stop a delayed teleport when a proxy request expires.
            return world.getChunkAtAsync(destination.chunkX(), destination.chunkZ(), true).thenApply(chunk -> null);
        }
        @Override public void mainThread(Runnable action) {
            if (!active) return;
            if (Bukkit.isPrimaryThread()) action.run();
            else getServer().getScheduler().runTask(NetworkChatBridge.this, () -> { if (active) action.run(); });
        }
        @Override public boolean teleport(TeleportService.Session session, TeleportService.Destination destination) {
            World world = getServer().getWorld(destination.worldId());
            if (world == null || !world.isChunkLoaded(destination.chunkX(), destination.chunkZ())) return false;
            Player player = (Player) session.identity();
            Location at = new Location(world, destination.x(), destination.y(), destination.z(), destination.yaw(), destination.pitch());
            // This API fires PlayerTeleportEvent and respects cancellation by inventory/protection plugins.
            return player.teleport(at, TeleportCause.PLUGIN);
        }
    }
}
