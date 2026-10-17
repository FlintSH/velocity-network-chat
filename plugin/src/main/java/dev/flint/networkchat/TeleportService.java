package dev.flint.networkchat;

import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.flint.networkchat.teleport.TeleportProtocol;
import dev.flint.networkchat.teleport.TeleportProtocol.Packet;
import dev.flint.networkchat.teleport.TeleportProtocol.Type;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;

/** One bounded coordinator per proxy. No database calls or blocking future waits. */
final class TeleportService {
    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(TeleportProtocol.CHANNEL);
    private final Object plugin;
    private final ProxyServer proxy;
    private final TeleportSettings config;
    private final Supplier<Settings> chatSettings;
    private final BiPredicate<Player, Player> contactAllowed;
    private final LongSupplier clock, nanoClock;
    private final Map<UUID, Request> requests = new HashMap<>();
    private final Map<UUID, Map<UUID, Request>> incoming = new HashMap<>();
    private final Map<UUID, Transfer> transfers = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final List<String> registered = new ArrayList<>();
    private ScheduledTask task;
    private boolean closed;

    private record Request(Player mover, Player target, long deadline) { }
    private enum Phase { PROBE, CONNECT, EXECUTE }
    private static final class Transfer {
        final UUID operation = UUID.randomUUID();
        final Player mover, target;
        final RegisteredServer destination;
        final String origin;
        final boolean staff;
        final long deadline, wallDeadline;
        Phase phase = Phase.PROBE;
        UUID nonce;
        ServerConnection carrier;
        long sentAt;
        Transfer(Player mover, Player target, RegisteredServer destination, String origin, boolean staff, long deadline, long wallDeadline) {
            this.mover = mover; this.target = target; this.destination = destination; this.origin = origin;
            this.staff = staff; this.deadline = deadline; this.wallDeadline = wallDeadline;
        }
    }

    TeleportService(Object plugin, ProxyServer proxy, TeleportSettings config, Supplier<Settings> chatSettings,
                    BiPredicate<Player, Player> contactAllowed) {
        this(plugin, proxy, config, chatSettings, contactAllowed, System::currentTimeMillis, System::nanoTime);
    }
    TeleportService(Object plugin, ProxyServer proxy, TeleportSettings config, Supplier<Settings> chatSettings,
                    BiPredicate<Player, Player> contactAllowed, LongSupplier clock, LongSupplier nanoClock) {
        this.plugin = plugin; this.proxy = proxy; this.config = config; this.chatSettings = chatSettings;
        this.contactAllowed = contactAllowed; this.clock = clock; this.nanoClock = nanoClock;
    }

    void validateAliases(Collection<String> chatAliases) {
        Set<String> used = new HashSet<>(chatAliases);
        if (!config.bool("enabled")) return;
        for (String kind : TeleportSettings.COMMANDS) for (String alias : config.aliases(kind))
            if (!used.add(alias) || proxy.getCommandManager().hasCommand(alias))
                throw new IllegalArgumentException("Command already registered or duplicated: /" + alias);
    }

    void start() {
        // Always claim the channel, including when disabled, so clients cannot spoof the proxy.
        proxy.getChannelRegistrar().register(CHANNEL);
        if (!config.bool("enabled")) return;
        for (String kind : TeleportSettings.COMMANDS) {
            List<String> aliases = config.aliases(kind);
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder(aliases.get(0))
                .aliases(aliases.subList(1, aliases.size()).toArray(String[]::new)).plugin(plugin).build(), new TeleportCommand(kind));
            registered.addAll(aliases);
        }
        task = proxy.getScheduler().buildTask(plugin, this::tick).repeat(500, TimeUnit.MILLISECONDS).schedule();
    }

    synchronized void close() {
        closed = true;
        if (task != null) task.cancel();
        registered.forEach(proxy.getCommandManager()::unregister); registered.clear();
        for (Transfer transfer : List.copyOf(transfers.values())) cancelBackend(transfer);
        requests.clear(); incoming.clear(); transfers.clear(); cooldowns.clear();
        proxy.getChannelRegistrar().unregister(CHANNEL);
    }

    private boolean permitted(CommandSource player, String kind) {
        String node = config.get("permission." + kind);
        return node.isEmpty() || player.hasPermission(node);
    }
    private String server(Player player) { return player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(""); }
    private boolean connected(Player player) { return player.isActive() && proxy.getPlayer(player.getUniqueId()).orElse(null) == player; }
    private boolean usable(Player from, Player to, boolean staff) {
        return usable(from, to, staff, server(from));
    }
    private boolean usable(Player from, Player to, boolean staff, String origin) {
        Settings chat = chatSettings.get();
        if (chat == null || !connected(from) || !connected(to) || !permitted(from, staff ? "staff" : "request")) return false;
        if (!staff && (!permitted(to, "request") || !contactAllowed.test(from, to))) return false;
        String destination = server(to);
        if (!config.serverAllowed(origin) || !config.serverAllowed(destination) || chat.excluded(origin) || chat.excluded(destination)) return false;
        if (to.hasPermission(chat.get("permission.hidden")) && !from.hasPermission(chat.get("permission.see-hidden"))) return false;
        return !config.bool("respect-chat-groups") || chat.group(origin).equals(chat.group(destination));
    }
    private void tell(CommandSource player, String key) { tell(player, key, Map.of()); }
    private void tell(CommandSource player, String key, Map<String, String> values) {
        if (!(player instanceof Player p) || connected(p)) player.sendMessage(config.message(key, values));
    }
    private Map<String, String> names(Player mover, Player target) {
        return Map.of("player", mover.getUsername(), "target", target.getUsername(), "seconds", config.get("request-seconds"),
            "accept", config.aliases("accept").get(0), "deny", config.aliases("deny").get(0), "cancel", config.aliases("cancel").get(0));
    }

    private final class TeleportCommand implements SimpleCommand {
        private final String kind;
        TeleportCommand(String kind) { this.kind = kind; }
        // Returning false would let a backend plugin execute the denied command.
        @Override public boolean hasPermission(Invocation invocation) { return true; }
        @Override public void execute(Invocation invocation) { executeCommand(kind, invocation.source(), invocation.arguments()); }
        @Override public List<String> suggest(Invocation invocation) { return suggestions(kind, invocation.source(), invocation.arguments()); }
    }

    synchronized void executeCommand(String kind, CommandSource source, String[] args) {
        if (closed) return;
        if (!(source instanceof Player player)) { tell(source, "players-only"); return; }
        if (!permitted(player, kind.equals("staff") ? "staff" : "request")) { tell(player, "no-permission"); return; }
        if (!config.configured()) { tell(player, "disabled"); return; }
        // Commands touch only this player's requests. The scheduled sweep handles network-wide expiry.
        Request outgoing = requests.get(player.getUniqueId());
        if (outgoing != null) expire(outgoing, nanoClock.getAsLong());
        for (Request pending : List.copyOf(incoming.getOrDefault(player.getUniqueId(), Map.of()).values()))
            expire(pending, nanoClock.getAsLong());
        int min = kind.equals("request") || kind.equals("staff") ? 1 : 0;
        int max = kind.equals("cancel") ? 0 : 1;
        if (args.length < min || args.length > max) {
            tell(player, "usage-" + kind, Map.of("command", config.aliases(kind).get(0))); return;
        }
        if (kind.equals("request") || kind.equals("staff")) {
            Player target = proxy.getPlayer(args[0]).orElse(null);
            if (target == null || !usable(player, target, kind.equals("staff"))) { tell(player, "unavailable"); return; }
            if (player == target) { tell(player, "self"); return; }
            UUID id = player.getUniqueId();
            if (requests.containsKey(id) || transfers.containsKey(id)) { tell(player, "busy", names(player, target)); return; }
            if (!kind.equals("staff")) {
                long now = nanoClock.getAsLong();
                Long last = cooldowns.get(id);
                if (!permitted(player, "bypass-cooldown") && last != null && now - last < TimeUnit.SECONDS.toNanos(config.number("cooldown-seconds"))) {
                    tell(player, "cooldown"); return;
                }
                Map<UUID, Request> inbox = incoming.computeIfAbsent(target.getUniqueId(), id2 -> new HashMap<>());
                for (Request pending : List.copyOf(inbox.values())) expire(pending, now);
                inbox = incoming.computeIfAbsent(target.getUniqueId(), id2 -> new HashMap<>());
                if (inbox.size() >= config.number("max-incoming")) { tell(player, "full"); return; }
                cooldowns.put(id, now);
                Request request = new Request(player, target, now + TimeUnit.SECONDS.toNanos(config.number("request-seconds")));
                requests.put(id, request); inbox.put(id, request);
                tell(player, "sent", names(player, target)); tell(target, "received", names(player, target));
            } else begin(player, target, true);
            return;
        }
        if (kind.equals("cancel")) {
            Request request = requests.get(player.getUniqueId());
            if (request == null) { tell(player, "none"); return; }
            remove(request);
            tell(player, "cancelled"); tell(request.target, "cancelled"); return;
        }
        List<Request> matching = incoming.getOrDefault(player.getUniqueId(), Map.of()).values().stream().filter(r -> r.target == player)
            .filter(r -> args.length == 0 || r.mover.getUsername().equalsIgnoreCase(args[0])).toList();
        if (matching.isEmpty()) { tell(player, "none"); return; }
        if (matching.size() > 1) { tell(player, "choose", Map.of("command", config.aliases(kind).get(0))); return; }
        Request request = matching.get(0);
        remove(request);
        if (kind.equals("deny")) {
            tell(player, "denied"); tell(request.mover, "declined", names(request.mover, player)); return;
        }
        if (!usable(request.mover, player, false)) { tell(player, "unavailable"); tell(request.mover, "unavailable"); return; }
        tell(player, "accepted", names(request.mover, player)); begin(request.mover, player, false);
    }

    private void begin(Player mover, Player target, boolean staff) {
        if (transfers.size() >= config.number("max-transfers")) { tell(mover, "full"); return; }
        RegisteredServer destination = proxy.getServer(server(target)).orElse(null);
        if (destination == null || transfers.containsKey(mover.getUniqueId())) { tell(mover, "unavailable"); return; }
        int timeout = config.number("transfer-seconds");
        Transfer transfer = new Transfer(mover, target, destination, server(mover), staff,
            nanoClock.getAsLong() + TimeUnit.SECONDS.toNanos(timeout), clock.getAsLong() + timeout * 1000L);
        transfers.put(mover.getUniqueId(), transfer);
        tell(mover, "starting", names(mover, target)); send(transfer);
    }

    synchronized void tick() {
        if (closed) return;
        long now = nanoClock.getAsLong();
        for (Request request : List.copyOf(requests.values())) expire(request, now);
        for (Transfer transfer : List.copyOf(transfers.values())) {
            if (now >= transfer.deadline || clock.getAsLong() >= transfer.wallDeadline) {
                finish(transfer, transfer.phase == Phase.PROBE ? "bridge-unavailable" : "timeout"); continue;
            }
            if (!valid(transfer)) { finish(transfer, "failed"); continue; }
            if (transfer.phase == Phase.CONNECT) continue;
            if (transfer.nonce == null || now - transfer.sentAt >= TimeUnit.SECONDS.toNanos(1)) send(transfer);
        }
    }

    private void remove(Request request) {
        if (!requests.remove(request.mover.getUniqueId(), request)) return;
        Map<UUID, Request> inbox = incoming.get(request.target.getUniqueId());
        if (inbox != null) {
            inbox.remove(request.mover.getUniqueId(), request);
            if (inbox.isEmpty()) incoming.remove(request.target.getUniqueId());
        }
    }
    private void expire(Request request, long now) {
        if (now < request.deadline && usable(request.mover, request.target, false)) return;
        remove(request);
        tell(request.mover, "expired", Map.of("player", request.target.getUsername()));
        tell(request.target, "expired", Map.of("player", request.mover.getUsername()));
    }

    private boolean valid(Transfer t) {
        String current = server(t.mover), destination = t.destination.getServerInfo().getName();
        // Velocity temporarily clears the old connection during an asynchronous backend transition.
        String policyOrigin = t.phase == Phase.CONNECT && current.isEmpty() ? t.origin : current;
        if (!usable(t.mover, t.target, t.staff, policyOrigin) || !server(t.target).equals(destination)) return false;
        return t.phase == Phase.PROBE ? current.equals(t.origin)
            : t.phase == Phase.EXECUTE ? current.equals(destination) : current.isEmpty() || current.equals(t.origin) || current.equals(destination);
    }
    private void send(Transfer transfer) {
        if (!valid(transfer)) { finish(transfer, "failed"); return; }
        Player carrier = transfer.phase == Phase.PROBE ? transfer.target : transfer.mover;
        ServerConnection connection = carrier.getCurrentServer().orElse(null);
        if (connection == null) { finish(transfer, "failed"); return; }
        long now = clock.getAsLong();
        if (now >= transfer.wallDeadline || nanoClock.getAsLong() >= transfer.deadline) return;
        transfer.nonce = UUID.randomUUID(); transfer.carrier = connection; transfer.sentAt = nanoClock.getAsLong();
        Packet packet = new Packet(transfer.phase == Phase.PROBE ? Type.PROBE : Type.EXECUTE, transfer.operation, transfer.nonce,
            transfer.mover.getUniqueId(), transfer.target.getUniqueId(), now,
            transfer.phase == Phase.PROBE ? transfer.wallDeadline : Math.min(transfer.wallDeadline, now + 3000));
        connection.sendPluginMessage(CHANNEL, TeleportProtocol.encode(packet, config.get("shared-secret")));
    }

    synchronized void message(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (closed || !config.configured() || !(event.getSource() instanceof ServerConnection connection)) return;
        Packet packet;
        try { packet = TeleportProtocol.decode(event.getData(), config.get("shared-secret"), clock.getAsLong()); }
        catch (IllegalArgumentException ignored) { return; }
        Transfer transfer = transfers.get(packet.mover());
        if (transfer == null || transfer.carrier != connection || !transfer.operation.equals(packet.operation())
            || !Objects.equals(transfer.nonce, packet.nonce()) || !transfer.target.getUniqueId().equals(packet.target())) return;
        if (nanoClock.getAsLong() >= transfer.deadline || !valid(transfer)) { finish(transfer, "failed"); return; }
        if (packet.type() == Type.FAILED) { finish(transfer, "failed"); return; }
        if (packet.type() == Type.WAIT) return;
        if (transfer.phase == Phase.EXECUTE && packet.type() == Type.SUCCESS) { finish(transfer, "success"); return; }
        if (transfer.phase != Phase.PROBE || packet.type() != Type.READY) return;
        if (server(transfer.mover).equals(transfer.destination.getServerInfo().getName())) {
            transfer.phase = Phase.EXECUTE; send(transfer); return;
        }
        transfer.phase = Phase.CONNECT; transfer.nonce = null;
        try {
            transfer.mover.createConnectionRequest(transfer.destination).connect().whenComplete((result, error) -> {
                synchronized (TeleportService.this) {
                    if (closed || transfers.get(transfer.mover.getUniqueId()) != transfer) return;
                    if (error != null || result == null || !result.isSuccessful() || !valid(transfer)
                        || !server(transfer.mover).equals(transfer.destination.getServerInfo().getName())
                        || nanoClock.getAsLong() >= transfer.deadline) { finish(transfer, "failed"); return; }
                    transfer.phase = Phase.EXECUTE; send(transfer);
                }
            });
        } catch (RuntimeException error) { finish(transfer, "failed"); }
    }

    private void finish(Transfer transfer, String message) {
        if (transfers.remove(transfer.mover.getUniqueId(), transfer)) {
            if (!message.equals("success")) cancelBackend(transfer);
            tell(transfer.mover, message, names(transfer.mover, transfer.target));
        }
    }

    private void cancelBackend(Transfer transfer) {
        ServerConnection connection = transfer.target.getCurrentServer()
            .filter(c -> c.getServerInfo().getName().equals(transfer.destination.getServerInfo().getName())).orElse(transfer.carrier);
        if (connection == null) return;
        long now = clock.getAsLong();
        try {
            connection.sendPluginMessage(CHANNEL, TeleportProtocol.encode(new Packet(Type.CANCEL, transfer.operation, UUID.randomUUID(),
                transfer.mover.getUniqueId(), transfer.target.getUniqueId(), now, now + 3000), config.get("shared-secret")));
        } catch (RuntimeException ignored) { /* Best effort: authenticated EXECUTE also has a strict expiry. */ }
    }

    synchronized void disconnect(Player player) {
        Request outgoing = requests.get(player.getUniqueId());
        if (outgoing != null && outgoing.mover == player) remove(outgoing);
        for (Request request : List.copyOf(incoming.getOrDefault(player.getUniqueId(), Map.of()).values()))
            if (request.target == player) remove(request);
        for (Transfer transfer : List.copyOf(transfers.values()))
            if (transfer.mover == player || transfer.target == player) finish(transfer, "failed");
        // A rejected duplicate login must not clear the active connection's cooldown.
        if (proxy.getPlayer(player.getUniqueId()).orElse(player) == player) cooldowns.remove(player.getUniqueId());
    }

    synchronized List<String> suggestions(String kind, CommandSource source, String[] args) {
        if (closed || !config.configured() || !(source instanceof Player player) || args.length > 1
            || kind.equals("cancel") || !permitted(player, kind.equals("staff") ? "staff" : "request")) return List.of();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        Collection<Player> candidates = kind.equals("accept") || kind.equals("deny")
            ? incoming.getOrDefault(player.getUniqueId(), Map.of()).values().stream().filter(r -> r.target == player && nanoClock.getAsLong() < r.deadline).map(Request::mover).toList()
            : proxy.getAllPlayers();
        return candidates.stream().filter(p -> p != player && usable(player, p, kind.equals("staff")))
            .map(Player::getUsername).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).limit(50).toList();
    }
}
