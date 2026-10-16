package dev.flint.networkchat;

import com.google.inject.Inject;
import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.*;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.proxy.*;
import com.velocitypowered.api.plugin.*;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NetworkChat {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean reloading = new AtomicBoolean();
    private volatile Settings settings;
    private final List<String> registered = new ArrayList<>();
    private boolean signedVelocity;
    private PlaceholderResolver placeholderResolver;
    private MessageRenderer renderer = new MessageRenderer(null);
    private final ChatCompletion completions;
    private ScheduledTask completionTask;
    private final ConcurrentMap<UUID, Presence> presence = new ConcurrentHashMap<>();
    private record Presence(Player owner, String name, String server, boolean hidden) { }

    @Inject
    public NetworkChat(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy; this.logger = logger; this.directory = directory;
        this.completions = new ChatCompletion(logger);
    }
    @Subscribe
    public void initialize(ProxyInitializeEvent event) {
        try {
            settings = Settings.load(directory);
            signedVelocity = proxy.getPluginManager().isLoaded("signedvelocity");
            if (proxy.getPluginManager().isLoaded("papiproxybridge")) {
                try { placeholderResolver = new PapiBridge(); placeholderResolver.configure(settings); logger.info("PAPIProxyBridge enabled for chat formatting"); }
                catch (LinkageError | RuntimeException error) { placeholderResolver = null; logger.warn("PAPIProxyBridge unavailable; using configured placeholder fallback", error); }
            }
            renderer = new MessageRenderer(placeholderResolver);
            // Validate every alias before registering any commands.
            for (String kind : kinds()) for (String alias : settings.list("command." + kind))
                if (proxy.getCommandManager().hasCommand(alias)) throw new IllegalArgumentException("Command already registered: /" + alias);
            for (String kind : kinds()) {
                List<String> aliases = new ArrayList<>(settings.list("command." + kind));
                proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder(aliases.get(0))
                    .aliases(aliases.subList(1, aliases.size()).toArray(String[]::new)).plugin(this).build(), new ChatCommand(kind));
                registered.addAll(aliases);
            }
            startCompletions();
            logger.info("Network Chat enabled for {} backend servers. SignedVelocity: {}", proxy.getAllServers().size(), signedVelocity);
            if (!signedVelocity) logger.warn("Signed chat capture is disabled. /g and /sc work; install SignedVelocity on proxy and backends for normal modern cross-server chat.");
        } catch (Exception exception) {
            registered.forEach(proxy.getCommandManager()::unregister);
            registered.clear(); settings = null;
            logger.error("Network Chat failed to initialize; no chat is intercepted", exception);
        }
    }
    void usePlaceholderResolver(PlaceholderResolver resolver) { placeholderResolver = resolver; renderer = new MessageRenderer(resolver); }
    private static List<String> kinds() { return List.of("global", "staff", "message", "reply", "ignore", "toggle", "channel", "admin"); }
    private Session session(Player player) { return sessions.compute(player.getUniqueId(), (id, current) -> current != null && current.owner == player ? current : new Session(player)); }
    private String server(Player player) { return player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(""); }
    private boolean permission(CommandSource source, Settings config, String name) {
        String node = config.get("permission." + name);
        return node.isEmpty() || source.hasPermission(node);
    }
    private boolean visible(Player viewer, Player target, Settings config) {
        return viewer.getUniqueId().equals(target.getUniqueId()) || !permission(target, config, "hidden") || permission(viewer, config, "see-hidden");
    }
    private boolean canCapture(Player player, Settings config) {
        // 760 = 1.19.1: denial can break a signed chat chain without backend coordination.
        return canCapture(player, config, server(player));
    }
    private boolean canCapture(Player player, Settings config, String backend) {
        return config.bool("chat.capture") && !config.excluded(backend) && !backend.isEmpty() && ((player.getProtocolVersion().getProtocol() >= 4 && player.getProtocolVersion().getProtocol() < 760)
            || (signedVelocity && config.signedBackend(backend)));
    }
    private void notice(CommandSource target, Settings config, String key) { notice(target, config, key, Map.of()); }
    private void notice(CommandSource target, Settings config, String key, Map<String, String> values) {
        if (target instanceof Player player) {
            renderer.render(config, "message." + key, player.getUniqueId(), values).thenAccept(rendered -> {
                if (player.isActive() && settings != null) player.sendMessage(rendered);
            });
        } else target.sendMessage(config.render("message." + key, values));
    }
    private boolean allowed(Player player, Settings config, String permission) {
        if (!permission(player, config, permission)) { notice(player, config, "no-permission"); return false; }
        return true;
    }
    private boolean validate(Player player, Settings config, String channel, String message) {
        if (!Text.valid(message, config.number("chat.max-length"))) {
            notice(player, config, "invalid-message", Map.of("limit", config.get("chat.max-length"))); return false;
        }
        String cooldown = switch (channel) { case "staff" -> "staff.cooldown-ms"; case "private" -> "private.cooldown-ms"; default -> "chat.cooldown-ms"; };
        // Global and local share a bucket so toggling cannot bypass anti-spam.
        String bucket = channel.equals("local") ? "global" : channel;
        if (!permission(player, config, "bypass-cooldown") && !session(player).allow(bucket, System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(config.number(cooldown)))) {
            notice(player, config, "cooldown"); return false;
        }
        return true;
    }
    private boolean availableServer(Player player, Settings config) {
        if (server(player).isEmpty() || config.excluded(server(player))) { notice(player, config, "excluded"); return false; }
        return true;
    }

    @Subscribe(order = PostOrder.LATE)
    public void chat(PlayerChatEvent event) {
        Settings config = settings;
        if (config == null || !event.getResult().isAllowed()) return;
        Player sender = event.getPlayer();
        Session state = session(sender);
        if (state.staffChat && !canCapture(sender, config)) {
            sender.disconnect(config.render("message.staff-disconnected", Map.of()));
            event.setResult(PlayerChatEvent.ChatResult.denied());
            return;
        }
        if (!canCapture(sender, config)) return;
        String channel = state.staffChat ? "staff" : state.channel;
        if (channel == null) channel = config.get("chat.default-channel");
        // Capture both global and local to enforce the same permissions/anti-spam.
        String message = event.getResult().getMessage().orElse(event.getMessage());
        event.setResult(PlayerChatEvent.ChatResult.denied());
        broadcast(sender, config, channel, message);
    }

    private void broadcast(Player sender, Settings config, String channel, String message) {
        if (!availableServer(sender, config) || !allowed(sender, config, channel + "-send") || !validate(sender, config, channel, message)) return;
        if (channel.equals("staff") && !allowed(sender, config, "staff-receive")) return;
        String origin = server(sender);
        Map<String, String> input = Map.of("player", sender.getUsername(), "server", config.label(origin), "message", message);
        Session state = session(sender);
        if (!state.enqueue(() -> renderer.render(config, "format." + channel, sender.getUniqueId(), input).thenAccept(rendered -> {
            Settings live = settings;
            if (!deliveryValid(sender, state, origin, live) || !permission(sender, live, channel + "-send")
                || (channel.equals("staff") && !permission(sender, live, "staff-receive"))) {
                if (sender.isActive() && live != null) notice(sender, live, "delivery-cancelled");
                return;
            }
            String group = live.group(origin);
            for (Player recipient : proxy.getAllPlayers()) {
                String destination = server(recipient);
                if (!recipient.isActive() || destination.isEmpty()) continue;
                if (channel.equals("staff")) {
                    if (permission(recipient, live, "staff-receive")) recipient.sendMessage(rendered);
                    continue;
                }
                if (live.excluded(destination)) continue;
                if (channel.equals("local") ? !destination.equals(origin) : !live.group(destination).equals(group)) continue;
                if (!permission(recipient, live, "global-receive")) continue;
                if (session(recipient).ignored.contains(sender.getUniqueId())) continue;
                recipient.sendMessage(rendered);
            }
        }))) notice(sender, config, "queue-full");
    }
    private boolean deliveryValid(Player sender, Session state, String origin, Settings config) {
        return config != null && sender.isActive() && sessions.get(sender.getUniqueId()) == state && server(sender).equals(origin) && !config.excluded(origin);
    }
    private boolean privateAvailable(Player sender, Player target, Settings config) {
        return config != null && config.bool("private.enabled") && permission(sender, config, "private")
            && target.isActive() && visible(sender, target, config) && !server(target).isEmpty() && !config.excluded(server(target))
            && (!config.bool("private.respect-groups") || config.group(server(sender)).equals(config.group(server(target))))
            && session(target).receiving && !session(target).ignored.contains(sender.getUniqueId()) && !session(sender).ignored.contains(target.getUniqueId());
    }

    private void message(Player sender, Settings config, Player target, String text) {
        if (!config.bool("private.enabled")) { notice(sender, config, "private-disabled"); return; }
        if (!allowed(sender, config, "private") || !availableServer(sender, config)) return;
        if (!config.bool("private.allow-self") && sender.getUniqueId().equals(target.getUniqueId())) { notice(sender, config, "self"); return; }
        if (!privateAvailable(sender, target, config)) { notice(sender, config, "unavailable"); return; }
        if (!validate(sender, config, "private", text)) return;
        Map<String, String> values = Map.of("player", sender.getUsername(), "target", target.getUsername(), "server", config.label(server(sender)), "message", text);
        Session state = session(sender), targetState = session(target);
        String origin = server(sender);
        if (!state.enqueue(() -> renderer.render(config, "format.private-received", sender.getUniqueId(), values)
            .thenCombine(renderer.render(config, "format.private-sent", sender.getUniqueId(), values), (received, sent) -> new Component[]{received, sent})
            .thenAccept(rendered -> {
                Settings live = settings;
                if (!deliveryValid(sender, state, origin, live) || sessions.get(target.getUniqueId()) != targetState || !privateAvailable(sender, target, live)) {
                    if (sender.isActive() && live != null) notice(sender, live, "unavailable"); return;
                }
                target.sendMessage(rendered[0]); sender.sendMessage(rendered[1]);
                state.reply = target.getUniqueId(); targetState.reply = sender.getUniqueId();
            }))) notice(sender, config, "queue-full");
    }

    @Subscribe(order = PostOrder.LATE)
    public void preConnect(ServerPreConnectEvent event) {
        Settings config = settings;
        Session state = sessions.get(event.getPlayer().getUniqueId());
        if (config == null || state == null || !state.staffChat || !event.getResult().isAllowed()) return;
        event.getResult().getServer().ifPresent(target -> {
            if (!canCapture(event.getPlayer(), config, target.getServerInfo().getName())) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied()); notice(event.getPlayer(), config, "staff-switch-blocked");
            }
        });
    }
    @Subscribe
    public void tabComplete(TabCompleteEvent event) {
        Settings config = settings;
        if (config != null) completions.complete(event, config, proxy.getAllPlayers(), (viewer, target) -> visible(viewer, target, config));
    }
    @Subscribe
    public void connected(ServerConnectedEvent event) {
        completions.backendChanged(event.getPlayer()); refreshCompletions();
        Settings config = settings;
        if (config == null) return;
        Player player = event.getPlayer();
        String backend = event.getServer().getServerInfo().getName();
        Presence now = new Presence(player, player.getUsername(), backend, permission(player, config, "hidden"));
        Presence previous = presence.put(player.getUniqueId(), now);
        // First backend connection only: switching servers is not a proxy join.
        if (previous == null && config.bool("announcements.join")) announce(player, config, now, true);
    }
    private void announce(Player subject, Settings config, Presence information, boolean join) {
        if (config.excluded(information.server())) return;
        String key = join ? "format.join" : "format.leave";
        Map<String, String> input = Map.of("player", information.name(), "server", config.label(information.server()));
        CompletableFuture<Component> rendered;
        if (join) rendered = renderer.render(config, key, subject.getUniqueId(), input);
        else {
            // Backend placeholder requests are unavailable after disconnection. Use a literal name.
            Map<String, Component> values = new HashMap<>();
            input.forEach((token, value) -> values.put(token, Component.text(value)));
            values.put("prefix", Component.empty());
            for (String expression : config.placeholders(key)) values.put(expression, Text.expansion(config.get("placeholders.fallback"), config.get("placeholders.format")));
            rendered = CompletableFuture.completedFuture(Text.renderComponents(config.template(key), values));
        }
        rendered.thenAccept(message -> {
            Settings live = settings;
            if (live == null || (join && (presence.get(subject.getUniqueId()) == null || !subject.isActive()))) return;
            boolean hidden = information.hidden() || (join && permission(subject, live, "hidden"));
            for (Player recipient : proxy.getAllPlayers()) {
                String destination = server(recipient);
                if (!recipient.isActive() || (!join && recipient.getUniqueId().equals(subject.getUniqueId())) || destination.isEmpty() || live.excluded(destination)) continue;
                if (!permission(recipient, live, "announcements")) continue;
                if (hidden && !permission(recipient, live, "see-hidden") && !recipient.getUniqueId().equals(subject.getUniqueId())) continue;
                if (live.get("announcements.scope").equals("group") && !live.group(destination).equals(live.group(information.server()))) continue;
                recipient.sendMessage(message);
            }
        });
    }
    private void refreshCompletions() {
        Settings config = settings;
        if (config != null) completions.refresh(config, proxy.getAllPlayers(), player -> permission(player, config, "hidden"), player -> permission(player, config, "see-hidden"));
    }
    private void startCompletions() {
        if (completionTask != null) completionTask.cancel();
        completionTask = proxy.getScheduler().buildTask(this, this::refreshCompletions)
            .repeat(settings.number("suggestions.refresh-ms"), TimeUnit.MILLISECONDS).schedule();
    }

    @Subscribe
    public void disconnect(DisconnectEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        // A rejected duplicate login can share the UUID of a still-connected player.
        // Only the actual connection owner may remove its presence/session state.
        Presence current = presence.get(id);
        if (current != null && current.owner() != event.getPlayer()) return;
        if (current != null && !presence.remove(id, current)) return;
        Session ownSession = sessions.get(id);
        if (ownSession != null && ownSession.owner != event.getPlayer()) return;
        Presence previous = current;
        Settings announcementConfig = settings;
        if (previous != null && announcementConfig != null && announcementConfig.bool("announcements.leave")) {
            Presence leaving = new Presence(event.getPlayer(), previous.name(), previous.server(), previous.hidden() || permission(event.getPlayer(), announcementConfig, "hidden"));
            announce(event.getPlayer(), announcementConfig, leaving, false);
        }
        if (ownSession != null && !sessions.remove(id, ownSession)) return;
        completions.disconnected(event.getPlayer());
        Settings config = settings;
        if (config != null) completions.refresh(config, proxy.getAllPlayers().stream().filter(p -> !p.getUniqueId().equals(id)).toList(),
            player -> permission(player, config, "hidden"), player -> permission(player, config, "see-hidden"));
        // Reply links are cleared; ignores survive the target's reconnect within the viewer's session.
        sessions.values().forEach(state -> { if (id.equals(state.reply)) state.reply = null; });
    }
    @Subscribe
    public void shutdown(ProxyShutdownEvent event) { settings = null; presence.clear(); if (completionTask != null) completionTask.cancel(); sessions.clear(); }

    private final class ChatCommand implements SimpleCommand {
        private final String kind;
        private ChatCommand(String kind) { this.kind = kind; }
        // Always claim our aliases, even when denied, so private/staff commands never fall through to a backend.
        @Override public boolean hasPermission(Invocation invocation) { return true; }
        @Override public void execute(Invocation invocation) {
            Settings config = settings;
            if (config == null) return;
            CommandSource source = invocation.source(); String[] args = invocation.arguments();
            if (kind.equals("admin")) { admin(source, config, args); return; }
            if (!(source instanceof Player player)) { notice(source, config, "players-only"); return; }
            switch (kind) {
                case "staff" -> {
                    if (args.length == 0 || (args.length == 1 && Set.of("on", "off").contains(args[0].toLowerCase(Locale.ROOT)))) {
                        Session state = session(player);
                        Boolean enabled = args.length == 0 ? null : args[0].equalsIgnoreCase("on");
                        // Turning off remains possible after permission revocation or disabling toggles.
                        if (Boolean.FALSE.equals(enabled) || (enabled == null && state.staffChat)) {
                            state.toggleStaff(false); notice(player, config, "staff-toggle", Map.of("state", "off")); return;
                        }
                        if (!config.bool("staff.toggle")) { notice(player, config, "staff-toggle-disabled"); return; }
                        if (!allowed(player, config, "staff-send") || !allowed(player, config, "staff-receive")) return;
                        if (!canCapture(player, config)) { notice(player, config, "staff-unsafe"); return; }
                        state.toggleStaff(true); notice(player, config, "staff-toggle", Map.of("state", "on"));
                    } else broadcast(player, config, "staff", String.join(" ", args));
                }
                case "global" -> {
                    if (args.length == 0) notice(player, config, "usage-" + kind);
                    else broadcast(player, config, kind, String.join(" ", args));
                }
                case "message" -> {
                    if (!allowed(player, config, "private")) return;
                    if (args.length < 2) { notice(player, config, "usage-message"); return; }
                    Optional<Player> target = proxy.getPlayer(args[0]);
                    if (target.isEmpty()) notice(player, config, "unavailable");
                    else message(player, config, target.get(), String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                }
                case "reply" -> {
                    if (!allowed(player, config, "private")) return;
                    if (args.length == 0) { notice(player, config, "usage-reply"); return; }
                    UUID id = session(player).reply;
                    Optional<Player> target = id == null ? Optional.empty() : proxy.getPlayer(id);
                    if (target.isEmpty()) notice(player, config, "no-reply");
                    else message(player, config, target.get(), String.join(" ", args));
                }
                case "toggle" -> {
                    if (!allowed(player, config, "private")) return;
                    if (args.length != 0) { notice(player, config, "toggle-usage"); return; }
                    notice(player, config, "toggle", Map.of("state", session(player).toggleReceiving() ? "on" : "off"));
                }
                case "ignore" -> {
                    if (!allowed(player, config, "private")) return;
                    if (args.length != 1) { notice(player, config, "usage-ignore"); return; }
                    Optional<Player> target = proxy.getPlayer(args[0]);
                    if (target.isEmpty() || !visible(player, target.get(), config)) { notice(player, config, "unavailable"); return; }
                    if (target.get().getUniqueId().equals(player.getUniqueId())) { notice(player, config, "self"); return; }
                    Session state = session(player);
                    UUID targetId = target.get().getUniqueId();
                    Boolean ignored = state.toggleIgnore(targetId);
                    if (ignored == null) { notice(player, config, "ignore-limit"); return; }
                    notice(player, config, "ignored", Map.of("target", target.get().getUsername(), "state", ignored ? "on" : "off"));
                }
                case "channel" -> {
                    if (args.length != 1 || !Set.of("global", "local").contains(args[0].toLowerCase(Locale.ROOT))) { notice(player, config, "invalid-channel"); return; }
                    if (!canCapture(player, config)) { notice(player, config, "unsafe-channel"); return; }
                    String channel = args[0].toLowerCase(Locale.ROOT);
                    if (!allowed(player, config, channel + "-send")) return;
                    session(player).channel = channel;
                    session(player).toggleStaff(false);
                    notice(player, config, "channel", Map.of("channel", channel));
                }
                default -> throw new IllegalStateException("Unknown command " + kind);
            }
        }
        @Override public List<String> suggest(Invocation invocation) {
            Settings config = settings;
            if (config == null) return List.of();
            String[] args = invocation.arguments();
            if (kind.equals("global") || kind.equals("staff") || kind.equals("reply") || (kind.equals("message") && args.length > 1)) {
                if (!(invocation.source() instanceof Player viewer)) return List.of();
                String permission = kind.equals("staff") ? "staff-send" : kind.equals("global") ? "global-send" : "private";
                if (!permission(viewer, config, permission)) return List.of();
                String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
                List<String> names = new ArrayList<>(playerSuggestions(viewer, config, prefix));
                if (kind.equals("staff") && args.length <= 1 && permission(viewer, config, "staff-receive"))
                    for (String option : List.of("on", "off")) if (option.startsWith(prefix)) names.add(option);
                return names;
            }
            if (args.length > 1) return List.of();
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            if (kind.equals("admin")) return permission(invocation.source(), config, "admin") ? List.of("reload", "status").stream().filter(s -> s.startsWith(prefix)).toList() : List.of();
            if (!(invocation.source() instanceof Player viewer)) return List.of();
            if (kind.equals("channel")) return List.of("global", "local").stream().filter(s -> s.startsWith(prefix)).toList();
            if (!Set.of("message", "ignore").contains(kind) || !permission(viewer, config, "private")) return List.of();
            return playerSuggestions(viewer, config, prefix);
        }
    }
    private List<String> playerSuggestions(Player viewer, Settings config, String prefix) {
        return proxy.getAllPlayers().stream().filter(Player::isActive).filter(p -> !p.getUniqueId().equals(viewer.getUniqueId()) && visible(viewer, p, config))
            .map(Player::getUsername).filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER)
            .limit(config.number("suggestions.limit")).toList();
    }
    private void admin(CommandSource source, Settings config, String[] args) {
        if (!permission(source, config, "admin")) { notice(source, config, "no-permission"); return; }
        if (args.length != 1) { notice(source, config, "usage-admin"); return; }
        if (args[0].equalsIgnoreCase("status")) {
            source.sendMessage(config.render("message.status", Map.of("players", Integer.toString(proxy.getPlayerCount()),
                "sessions", Integer.toString(sessions.size()), "signed", Boolean.toString(signedVelocity), "capture", config.get("chat.capture")))); return;
        }
        if (!args[0].equalsIgnoreCase("reload")) { notice(source, config, "usage-admin"); return; }
        if (!reloading.compareAndSet(false, true)) { notice(source, config, "reload-busy"); return; }
        proxy.getScheduler().buildTask(this, () -> {
            try {
                Settings replacement = Settings.load(directory);
                if (settings != null) {
                    if (placeholderResolver != null) placeholderResolver.configure(replacement);
                    settings = replacement; startCompletions(); refreshCompletions(); notice(source, replacement, "reload");
                }
            } catch (Exception exception) {
                logger.error("Configuration reload rejected", exception); notice(source, config, "reload-failed");
            } finally { reloading.set(false); }
        }).schedule();
    }
}
