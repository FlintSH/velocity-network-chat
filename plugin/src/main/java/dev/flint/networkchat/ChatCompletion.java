package dev.flint.networkchat;

import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.util.GameProfile;
import org.slf4j.Logger;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Function;

/** Modern custom suggestions, legacy request completion, and optional 1.13–1.19 TAB fallback. */
final class ChatCompletion {
    private record Delta(Set<String> added, Set<String> removed) { }
    private Set<String> allNames = Set.of(), publicNames = Set.of();
    private record State(Set<String> custom, Map<UUID, String> tab) { }
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Set<UUID> resend = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Method add, remove;
    private final Logger logger;
    ChatCompletion(Logger logger) { this(logger, Player.class); }
    ChatCompletion(Logger logger, Class<?> playerApi) {
        this.logger = logger;
        Method a = null, r = null;
        try {
            a = playerApi.getMethod("addCustomChatCompletions", Collection.class);
            r = playerApi.getMethod("removeCustomChatCompletions", Collection.class);
        } catch (NoSuchMethodException ignored) { /* Older Velocity has no modern clients/API. */ }
        add = a; remove = r;
    }
    void complete(TabCompleteEvent event, Settings config, Collection<Player> players, BiPredicate<Player, Player> visible) {
        if (!config.bool("suggestions.chat") || event.getPartialMessage().startsWith("/")) return;
        String partial = event.getPartialMessage();
        int space = partial.lastIndexOf(' ');
        String prefix = partial.substring(space + 1).toLowerCase(Locale.ROOT);
        List<String> names = players.stream().filter(Player::isActive).filter(p -> visible.test(event.getPlayer(), p))
            .map(Player::getUsername).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER)
            .limit(config.number("suggestions.chat-limit")).toList();
        // Remove known hidden network names from backend results as well.
        Set<String> hidden = new HashSet<>();
        players.stream().filter(p -> !visible.test(event.getPlayer(), p)).forEach(p -> hidden.add(p.getUsername().toLowerCase(Locale.ROOT)));
        event.getSuggestions().removeIf(name -> hidden.contains(name.toLowerCase(Locale.ROOT)));
        for (String name : names) if (!event.getSuggestions().contains(name)) event.getSuggestions().add(name);
    }
    void refresh(Settings config, Collection<Player> online, BiPredicate<Player, Player> visible) {
        reconcile(config, online, visible, null, null);
    }
    void refresh(Settings config, Collection<Player> online, Predicate<Player> hidden, Predicate<Player> seeHidden) {
        reconcile(config, online, null, hidden, seeHidden);
    }
    private void reconcile(Settings config, Collection<Player> online, BiPredicate<Player, Player> filter, Predicate<Player> hidden, Predicate<Player> seeHidden) {
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            List<Player> players = online.stream().filter(Player::isActive).sorted(Comparator.comparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER)).toList();
            Function<Player, Set<String>> catalog = null;
            BiPredicate<Player, Player> visible = filter;
            if (hidden != null) {
                // Evaluate permissions once per player, not once per sender/recipient pair.
                Set<UUID> hiddenIds = new HashSet<>(), privileged = new HashSet<>();
                for (Player player : players) {
                    if (hidden.test(player)) hiddenIds.add(player.getUniqueId());
                    if (seeHidden.test(player)) privileged.add(player.getUniqueId());
                }
                visible = (viewer, target) -> viewer.getUniqueId().equals(target.getUniqueId()) || !hiddenIds.contains(target.getUniqueId()) || privileged.contains(viewer.getUniqueId());
                int limit = config.number("suggestions.chat-limit");
                Set<String> full = players.stream().map(Player::getUsername).limit(limit).collect(java.util.stream.Collectors.toSet());
                Set<String> exposed = players.stream().filter(p -> !hiddenIds.contains(p.getUniqueId())).map(Player::getUsername).limit(limit).collect(java.util.stream.Collectors.toSet());
                // Reuse immutable catalogs so unchanged modern clients require no per-name diff work.
                if (!allNames.equals(full)) allNames = Set.copyOf(full);
                if (!publicNames.equals(exposed)) publicNames = Set.copyOf(exposed);
                catalog = viewer -> {
                    if (privileged.contains(viewer.getUniqueId())) return allNames;
                    if (!hiddenIds.contains(viewer.getUniqueId())) return publicNames;
                    Set<String> own = new HashSet<>(publicNames);
                    if (own.size() < limit) own.add(viewer.getUsername());
                    return Set.copyOf(own);
                };
            }
            Map<Set<String>, Map<Set<String>, Delta>> diffs = new IdentityHashMap<>();
            Set<UUID> active = new HashSet<>();
            for (Player viewer : players) {
                active.add(viewer.getUniqueId());
                try { refreshPlayer(viewer, players, config, visible, catalog == null ? null : catalog.apply(viewer), diffs); }
                catch (ReflectiveOperationException | RuntimeException error) { logger.debug("Could not update chat suggestions for {}", viewer.getUniqueId(), error); }
            }
            states.keySet().removeIf(id -> !active.contains(id));
            resend.removeIf(id -> !active.contains(id));
        } finally { refreshing.set(false); }
    }
    private void refreshPlayer(Player viewer, List<Player> players, Settings config, BiPredicate<Player, Player> visible, Set<String> catalog, Map<Set<String>, Map<Set<String>, Delta>> diffs) throws ReflectiveOperationException {
        State old = states.getOrDefault(viewer.getUniqueId(), new State(Set.of(), Map.of()));
        int protocol = viewer.getProtocolVersion().getProtocol();
        boolean enabled = config.bool("suggestions.chat");
        Set<String> desiredCustom = new LinkedHashSet<>();
        Map<UUID, String> desiredTab = new LinkedHashMap<>();
        if (enabled && protocol >= 760 && add != null) {
            if (catalog != null) desiredCustom = catalog;
            else players.stream().filter(p -> visible.test(viewer, p)).map(Player::getUsername)
                .limit(config.number("suggestions.chat-limit")).forEach(desiredCustom::add);
        } else if (enabled && protocol >= 393 && protocol < 760 && config.bool("suggestions.legacy-tab-list")) {
            String local = server(viewer);
            players.stream().filter(p -> !server(p).equals(local) && visible.test(viewer, p))
                .limit(config.number("suggestions.legacy-tab-limit")).forEach(p -> desiredTab.put(ownedId(p.getUniqueId()), p.getUsername()));
        }
        if (remove != null) {
            Set<String> desired = desiredCustom;
            Delta delta;
            if (old.custom() == desired) delta = new Delta(Set.of(), Set.of());
            else delta = diffs.computeIfAbsent(old.custom(), ignored -> new IdentityHashMap<>()).computeIfAbsent(desired, ignored -> {
                Set<String> added = new HashSet<>(desired); added.removeAll(old.custom());
                Set<String> removed = new HashSet<>(old.custom()); removed.removeAll(desired);
                return new Delta(Set.copyOf(added), Set.copyOf(removed));
            });
            if (!delta.removed().isEmpty()) remove.invoke(viewer, delta.removed());
            Set<String> added = resend.remove(viewer.getUniqueId()) ? desired : delta.added();
            if (!added.isEmpty()) add.invoke(viewer, added);
        }
        TabList tab = viewer.getTabList();
        if (tab != null) {
            old.tab().keySet().stream().filter(id -> !desiredTab.containsKey(id)).forEach(tab::removeEntry);
            desiredTab.forEach((id, name) -> {
                if (!tab.containsEntry(id)) tab.addEntry(tab.buildEntry(new GameProfile(id, name, List.of()), null, 0, 0));
            });
        }
        states.put(viewer.getUniqueId(), new State(Set.copyOf(desiredCustom), Map.copyOf(desiredTab)));
    }
    private static String server(Player player) { return player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(""); }
    static UUID ownedId(UUID id) { return UUID.nameUUIDFromBytes(("networkchat:" + id).getBytes(StandardCharsets.UTF_8)); }
    void disconnected(Player player) { states.remove(player.getUniqueId()); resend.remove(player.getUniqueId()); }
    void backendChanged(Player player) {
        // Backends can clear custom suggestions on switch; re-send our names on the next refresh.
        resend.add(player.getUniqueId());
    }
}
