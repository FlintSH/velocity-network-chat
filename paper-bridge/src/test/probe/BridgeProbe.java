package dev.flint.networkchat.probe;

import dev.flint.networkchat.teleport.TeleportProtocol;
import dev.flint.networkchat.teleport.TeleportProtocol.Packet;
import dev.flint.networkchat.teleport.TeleportProtocol.Type;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;

/** Real Paper integration with controlled player lookup and test-only inventory readiness. */
public final class BridgeProbe extends JavaPlugin implements Listener {
    Object service;
    Plugin bridge, inventory;
    String secret;
    Fake mover, target;
    boolean cancelTeleport;
    int teleports;

    @Override public void onEnable() {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                bridge = Bukkit.getPluginManager().getPlugin("NetworkChatBridge");
                inventory = Bukkit.getPluginManager().getPlugin("NetworkInventorySync");
                check(bridge != null && bridge.isEnabled(), "Bridge did not enable");
                secret = ((JavaPlugin) bridge).getConfig().getString("shared-secret");
                service = field(bridge, "service");
                Location spawn = Bukkit.getWorlds().getFirst().getSpawnLocation();
                mover = new Fake(spawn.clone());
                target = new Fake(spawn.clone().add(2, 0, 2));
                target.location.setYaw(90);
                Object platform = field(service, "platform");
                Class<?> platformType = platform.getClass().getInterfaces()[0];
                Class<?> sessionType = Class.forName("dev.flint.networkchat.bridge.TeleportService$Session", true, bridge.getClass().getClassLoader());
                Constructor<?> constructor = sessionType.getDeclaredConstructors()[0];
                constructor.setAccessible(true);
                Object lookup = Proxy.newProxyInstance(platformType.getClassLoader(), new Class<?>[]{platformType}, (proxy, method, args) -> {
                    if (method.getName().equals("player")) {
                        Fake fake = mover.id.equals(args[0]) ? mover : target.id.equals(args[0]) ? target : null;
                        return fake == null ? null : constructor.newInstance(fake.id, fake.player, true, true, fake.location.getWorld().getName());
                    }
                    method.setAccessible(true);
                    try { return method.invoke(platform, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
                Field platformField = service.getClass().getDeclaredField("platform");
                platformField.setAccessible(true);
                platformField.set(service, lookup);
                Bukkit.getPluginManager().registerEvents(this, this);
                runChecks();
            } catch (Throwable error) { fail(error); }
        });
    }

    void runChecks() throws Exception {
        UUID operation = UUID.randomUUID();
        Packet first = packet(Type.PROBE, operation);
        byte[] forged = TeleportProtocol.encode(first, secret);
        forged[10] ^= 1;
        deliver(forged);
        check(operations().isEmpty(), "Forged packet reached service");
        deliver(Arrays.copyOf(forged, 2));
        check(operations().isEmpty(), "Truncated packet reached service");
        long now = System.currentTimeMillis();
        deliver(TeleportProtocol.encode(new Packet(Type.PROBE, operation, UUID.randomUUID(), mover.id, target.id, now - 2000, now - 1), secret));
        check(operations().isEmpty(), "Expired packet reached service");

        readiness(false);
        send(first);
        check(result(first) == Type.WAIT, "Loading inventory must wait during probe");
        readiness(true);
        Packet probe = packet(Type.PROBE, operation);
        send(probe);
        check(result(probe) == Type.READY, "Ready inventory did not pass probe");
        readiness(false);
        Packet waiting = packet(Type.EXECUTE, operation);
        send(waiting);
        check(result(waiting) == Type.WAIT && teleports == 0, "Inventory loading did not prevent teleport");

        readiness(true);
        cancelTeleport = true;
        send(packet(Type.EXECUTE, operation));
        waitFor(() -> terminal(operation) != null, () -> {
            check(terminal(operation) == Type.FAILED && teleports == 1, "Cancelled event was not a failed operation");
            cancelTeleport = false;
            send(packet(Type.EXECUTE, operation));
            check(teleports == 1, "Retry bypassed a cancelled teleport");
            UUID success = UUID.randomUUID();
            send(packet(Type.PROBE, success));
            Packet execute = packet(Type.EXECUTE, success);
            send(execute);
            waitFor(() -> terminal(success) != null, () -> {
                check(terminal(success) == Type.SUCCESS, "Allowed teleport did not succeed");
                check(mover.location.equals(target.location), "Destination differs from target position/orientation");
                check(teleports == 2, "Unexpected teleport count");
                send(execute);
                send(packet(Type.EXECUTE, success));
                check(teleports == 2, "Duplicate successful operation teleported again");
                getLogger().info("BRIDGE_PROBE_OK: real Paper 26.1.2 startup, plugin-channel authentication, forged/truncated/expired rejection, main-thread inventory readiness, async chunks, cancellation, target position and duplicate execution");
                Bukkit.shutdown();
            });
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void teleport(PlayerTeleportEvent event) {
        if (event.getPlayer() != mover.player) return;
        check(Bukkit.isPrimaryThread(), "Teleport ran off main thread");
        check(event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN, "Wrong teleport cause");
        if (cancelTeleport) event.setCancelled(true);
    }

    Packet packet(Type type, UUID operation) {
        long now = System.currentTimeMillis();
        return new Packet(type, operation, UUID.randomUUID(), mover.id, target.id, now, now + 10000);
    }
    void send(Packet packet) { deliver(TeleportProtocol.encode(packet, secret)); }
    void deliver(byte[] bytes) { ((PluginMessageListener) bridge).onPluginMessageReceived(TeleportProtocol.CHANNEL, target.player, bytes); }
    void readiness(boolean ready) throws Exception { inventory.getClass().getField("ready").setBoolean(inventory, ready); }
    Map<?, ?> operations() { return (Map<?, ?>) field(service, "operations"); }
    Type terminal(UUID operation) { return (Type) field(operations().get(operation), "terminal"); }
    Type result(Packet packet) { return (Type) field(((Map<?, ?>) field(service, "nonces")).get(packet.nonce()), "result"); }
    static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    void waitFor(BooleanSupplier condition, Runnable success) {
        new BukkitRunnable() {
            int ticks;
            @Override public void run() {
                try {
                    if (condition.getAsBoolean()) { cancel(); success.run(); }
                    else if (++ticks > 240) { cancel(); fail(new IllegalStateException("Probe timed out")); }
                } catch (Throwable error) { cancel(); fail(error); }
            }
        }.runTaskTimer(this, 1, 1);
    }
    void fail(Throwable error) {
        getLogger().severe("BRIDGE_PROBE_FAILED: " + error);
        error.printStackTrace();
        Bukkit.shutdown();
    }
    static void check(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }

    final class Fake {
        final UUID id = UUID.randomUUID();
        final Player player;
        Location location;
        Fake(Location initial) {
            location = initial;
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "BridgeProbe";
                    case "getLocation" -> location.clone();
                    case "getWorld" -> location.getWorld();
                    case "isOnline", "isValid" -> true;
                    case "isDead" -> false;
                    case "teleport" -> {
                        teleports++;
                        Location to = (Location) args[0];
                        var event = new PlayerTeleportEvent((Player) proxy, location.clone(), to,
                                (PlayerTeleportEvent.TeleportCause) args[1]);
                        Bukkit.getPluginManager().callEvent(event);
                        if (event.isCancelled()) yield false;
                        location = event.getTo().clone();
                        yield true;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "BridgeProbe[" + id + "]";
                    default -> defaultValue(method.getReturnType());
                };
            });
        }
    }
    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }
}
