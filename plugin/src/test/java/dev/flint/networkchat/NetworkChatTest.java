package dev.flint.networkchat;

import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.Scheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NetworkChatTest {
    @TempDir Path directory;
    ProxyServer proxy; CommandManager manager; NetworkChat plugin;
    final Map<String, SimpleCommand> commands = new HashMap<>();
    final List<Player> players = new ArrayList<>();
    final Map<Player, Set<String>> permissions = new HashMap<>();
    final Map<Player, List<String>> received = new HashMap<>();
    @BeforeEach void setup() throws Exception { initialize(false, "chat.cooldown-ms=0\nprivate.cooldown-ms=0\nstaff.cooldown-ms=0\n"); }
    void initialize(boolean signed, String config) throws Exception {
        Files.writeString(directory.resolve("config.properties"), config);
        commands.clear(); proxy = mock(ProxyServer.class); manager = mock(CommandManager.class);
        PluginManager plugins = mock(PluginManager.class);
        when(proxy.getPluginManager()).thenReturn(plugins); when(plugins.isLoaded("signedvelocity")).thenReturn(signed);
        when(proxy.getCommandManager()).thenReturn(manager); when(proxy.getAllPlayers()).thenAnswer(i -> List.copyOf(players));
        when(proxy.getAllServers()).thenReturn(List.of());
        when(proxy.getPlayer(anyString())).thenAnswer(i -> players.stream().filter(p -> p.getUsername().equalsIgnoreCase(i.getArgument(0))).findFirst());
        when(proxy.getPlayer(any(UUID.class))).thenAnswer(i -> players.stream().filter(p -> p.getUniqueId().equals(i.getArgument(0))).findFirst());
        when(manager.metaBuilder(anyString())).thenAnswer(i -> {
            List<String> aliases = new ArrayList<>(List.of((String) i.getArgument(0))); CommandMeta.Builder builder = mock(CommandMeta.Builder.class);
            when(builder.plugin(any())).thenReturn(builder);
            when(builder.aliases(any(String[].class))).thenAnswer(a -> { aliases.addAll(List.of((String[]) a.getRawArguments()[0])); return builder; });
            CommandMeta meta = mock(CommandMeta.class); when(meta.getAliases()).thenReturn(aliases); when(builder.build()).thenReturn(meta); return builder;
        });
        doAnswer(i -> { CommandMeta meta = i.getArgument(0); SimpleCommand command = i.getArgument(1); meta.getAliases().forEach(a -> commands.put(a, command)); return null; })
            .when(manager).register(any(CommandMeta.class), any(Command.class));
        Scheduler scheduler = mock(Scheduler.class); when(proxy.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(any(), any(Runnable.class))).thenAnswer(i -> {
            Runnable task = i.getArgument(1); Scheduler.TaskBuilder builder = mock(Scheduler.TaskBuilder.class);
            when(builder.repeat(anyLong(), any(java.util.concurrent.TimeUnit.class))).thenReturn(builder);
            when(builder.schedule()).thenAnswer(j -> { task.run(); return null; }); return builder;
        });
        Logger log = mock(Logger.class);
        plugin = new NetworkChat(proxy, log, directory); plugin.initialize(new ProxyInitializeEvent());
        assertFalse(commands.isEmpty());
    }
    Player player(String name, String server) {
        Player player = mock(Player.class); permissions.put(player, new HashSet<>()); received.put(player, new ArrayList<>());
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getUsername()).thenReturn(name);
        when(player.isActive()).thenReturn(true); when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_8);
        ServerConnection connection = mock(ServerConnection.class); when(connection.getServerInfo()).thenReturn(new ServerInfo(server, new java.net.InetSocketAddress("localhost", 25565)));
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        when(player.hasPermission(anyString())).thenAnswer(i -> permissions.get(player).contains(i.getArgument(0)));
        doAnswer(i -> { received.get(player).add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
            .when(player).sendMessage(any(Component.class));
        players.add(player); return player;
    }
    SimpleCommand.Invocation invocation(Player player, String... args) {
        SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class); when(invocation.source()).thenReturn(player); when(invocation.arguments()).thenReturn(args); return invocation;
    }
    void run(String alias, Player sender, String... args) { commands.get(alias).execute(invocation(sender, args)); }
    void grant(Player player, String... nodes) { permissions.get(player).addAll(List.of(nodes)); }
    void modern(Player player) { ProtocolVersion version = mock(ProtocolVersion.class); when(version.getProtocol()).thenReturn(770); when(player.getProtocolVersion()).thenReturn(version); }

    @Test void globalChatReachesDifferentBackendsExactlyOnce() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival");
        PlayerChatEvent event = new PlayerChatEvent(alice, "hello"); plugin.chat(event);
        assertFalse(event.getResult().isAllowed()); assertEquals(1, received.get(alice).size());
        assertEquals(List.of("[lobby] Alice: hello"), received.get(bob));
    }
    @Test void respectsRoutingGroupsExcludedServersAndLocalSelection() throws Exception {
        initialize(false, "routing.group.survival=survival\nchat.excluded-servers=auth\nchat.cooldown-ms=0\n");
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"), eve = player("Eve", "auth"), sam = player("Sam", "lobby");
        run("g", alice, "hello"); assertTrue(received.get(bob).isEmpty()); assertTrue(received.get(eve).isEmpty()); assertEquals(1, received.get(sam).size());
        run("chat", alice, "local"); plugin.chat(new PlayerChatEvent(alice, "local"));
        assertEquals("[Local] Alice: local", received.get(sam).get(1));
        PlayerChatEvent auth = new PlayerChatEvent(eve, "auth"); plugin.chat(auth); assertTrue(auth.getResult().isAllowed());
    }
    @Test void staffMessagesNeverReachUnprivilegedPlayersAndDoNotFallThrough() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"), eve = player("Eve", "survival");
        grant(alice, "networkchat.staff.send", "networkchat.staff.receive"); grant(bob, "networkchat.staff.receive");
        run("sc", alice, "secret"); assertEquals(1, received.get(bob).size()); assertTrue(received.get(eve).isEmpty());
        assertTrue(commands.get("sc").hasPermission(invocation(eve, "secret")));
        run("sc", eve, "secret"); assertEquals(List.of("You do not have permission."), received.get(eve)); assertEquals(1, received.get(bob).size());
    }
    @Test void modernChatPassesThroughUntilSignedBackendExplicitlyAcknowledged() throws Exception {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"); modern(alice);
        PlayerChatEvent event = new PlayerChatEvent(alice, "hello"); plugin.chat(event); assertTrue(event.getResult().isAllowed()); assertTrue(received.get(bob).isEmpty());
        run("g", alice, "hello"); assertEquals(1, received.get(bob).size());
        initialize(true, "chat.signed-backends=other\n"); event = new PlayerChatEvent(alice, "hello"); plugin.chat(event); assertTrue(event.getResult().isAllowed());
        initialize(true, "chat.signed-backends=lobby\n"); event = new PlayerChatEvent(alice, "hello"); plugin.chat(event); assertFalse(event.getResult().isAllowed()); assertEquals(2, received.get(bob).size());
    }
    @Test void respectsEarlierModerationCancellationAndModification() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival");
        PlayerChatEvent cancelled = new PlayerChatEvent(alice, "bad"); cancelled.setResult(PlayerChatEvent.ChatResult.denied()); plugin.chat(cancelled); assertTrue(received.get(bob).isEmpty());
        PlayerChatEvent modified = new PlayerChatEvent(alice, "bad"); modified.setResult(PlayerChatEvent.ChatResult.message("filtered")); plugin.chat(modified);
        assertEquals(List.of("[lobby] Alice: filtered"), received.get(bob));
    }
    @Test void privateMessagesAndReplyStayBetweenParticipantsAcrossServers() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"), eve = player("Eve", "lobby"); grant(alice, "networkchat.msg"); grant(bob, "networkchat.msg");
        run("msg", alice, "bOb", "hello"); run("r", bob, "hi");
        assertEquals(List.of("[You → Bob] hello", "[Bob → You] hi"), received.get(alice));
        assertEquals(List.of("[Alice → You] hello", "[You → Alice] hi"), received.get(bob)); assertTrue(received.get(eve).isEmpty());
    }
    @Test void ignoreAndReceivingToggleBlockPrivateDeliveryAndIgnoreBlocksGlobal() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"); grant(alice, "networkchat.msg"); grant(bob, "networkchat.msg");
        run("ignore", bob, "Alice"); run("msg", alice, "Bob", "secret"); run("g", alice, "public");
        assertEquals(List.of("Alice: ignore on."), received.get(bob));
        run("ignore", bob, "Alice"); run("msgtoggle", bob); run("msg", alice, "Bob", "secret");
        assertEquals(3, received.get(bob).size()); assertTrue(received.get(alice).contains("That player is unavailable."));
    }
    @Test void hiddenPlayersDoNotLeakThroughMessageLookupOrSuggestions() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"); grant(alice, "networkchat.msg"); grant(bob, "networkchat.hidden");
        assertTrue(commands.get("msg").suggest(invocation(alice, "B")).isEmpty()); run("msg", alice, "Bob", "secret");
        assertEquals(List.of("That player is unavailable."), received.get(alice)); assertTrue(received.get(bob).isEmpty());
        grant(alice, "networkchat.see.hidden"); assertEquals(List.of("Bob"), commands.get("msg").suggest(invocation(alice, "B")));
    }
    @Test void invalidReloadLeavesPreviousConfigActive() throws Exception {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"); grant(alice, "networkchat.admin");
        Files.writeString(directory.resolve("config.properties"), "chat.max-length=-1\n"); run("nchat", alice, "reload");
        run("g", alice, "hello"); assertEquals(List.of("[lobby] Alice: hello"), received.get(bob));
        assertTrue(received.get(alice).get(0).startsWith("Configuration invalid"));
    }
    @Test void successfulReloadUpdatesRoutingWithoutRestart() throws Exception {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"); grant(alice, "networkchat.admin");
        Files.writeString(directory.resolve("config.properties"), "routing.group.survival=isolated\n"); run("nchat", alice, "reload"); run("g", alice, "hello");
        assertTrue(received.get(bob).isEmpty()); assertTrue(received.get(alice).get(0).startsWith("Configuration reloaded"));
    }
    @Test void disconnectClearsReplyLinkAndDoesNotMessageReconnectedTarget() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"); grant(alice, "networkchat.msg"); grant(bob, "networkchat.msg");
        run("msg", alice, "Bob", "hello"); DisconnectEvent event = mock(DisconnectEvent.class); when(event.getPlayer()).thenReturn(bob); plugin.disconnect(event); players.remove(bob);
        run("r", alice, "hi"); assertEquals("Nobody has messaged you in this session.", received.get(alice).get(1));
    }
    @Test void longMessagesAndControlsNeverReachRecipients() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival");
        run("g", alice, "a".repeat(257)); run("g", alice, "a\nb"); assertTrue(received.get(bob).isEmpty());
    }
    @Test void ordinaryChatRateLimitIsEnforcedAndCannotBeBypassedByLocalSelection() throws Exception {
        initialize(false, "chat.cooldown-ms=60000\n");
        Player alice = player("Alice", "lobby"), bob = player("Bob", "lobby");
        run("g", alice, "first"); run("chat", alice, "local");
        PlayerChatEvent event = new PlayerChatEvent(alice, "second"); plugin.chat(event);
        assertFalse(event.getResult().isAllowed()); assertEquals(1, received.get(bob).size());
        assertEquals("Please wait before sending another message.", received.get(alice).get(2));
        grant(alice, "networkchat.bypass.cooldown"); plugin.chat(new PlayerChatEvent(alice, "third")); assertEquals(2, received.get(bob).size());
    }
    @Test void staffPermissionRevocationTakesEffectOnNextMessage() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "auth");
        grant(alice, "networkchat.staff.send", "networkchat.staff.receive"); grant(bob, "networkchat.staff.receive");
        run("sc", alice, "first"); permissions.get(bob).clear(); run("sc", alice, "second"); assertEquals(1, received.get(bob).size());
    }
    @Test void privateMessagingCanBeDisabledOrConfinedToGroups() throws Exception {
        initialize(false, "private.enabled=false\n");
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"); grant(alice, "networkchat.msg");
        run("msg", alice, "Bob", "secret"); assertEquals(List.of("Private messages are disabled."), received.get(alice)); assertTrue(received.get(bob).isEmpty());
        initialize(false, "private.respect-groups=true\nrouting.group.survival=isolated\n");
        run("msg", alice, "Bob", "secret"); assertEquals("That player is unavailable.", received.get(alice).get(1)); assertTrue(received.get(bob).isEmpty());
    }
    @Test void publicPermissionsGateSendingAndReceiving() throws Exception {
        initialize(false, "permission.global-send=chat.send\npermission.global-receive=chat.receive\n");
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival");
        plugin.chat(new PlayerChatEvent(alice, "denied")); assertTrue(received.get(bob).isEmpty());
        grant(alice, "chat.send", "chat.receive"); run("g", alice, "hello"); assertTrue(received.get(bob).isEmpty());
        grant(bob, "chat.receive"); grant(alice, "networkchat.bypass.cooldown"); run("g", alice, "hello"); assertEquals(1, received.get(bob).size());
    }
    @Test void sessionPreferencesResetWhenOwnerDisconnects() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"); grant(alice, "networkchat.msg"); grant(bob, "networkchat.msg");
        run("msgtoggle", bob); DisconnectEvent event = mock(DisconnectEvent.class); when(event.getPlayer()).thenReturn(bob); plugin.disconnect(event);
        run("msg", alice, "Bob", "hello"); assertEquals("[Alice → You] hello", received.get(bob).get(1));
    }
    @Test void staffModeTogglesOrdinaryChatWithoutExposingItToPlayers() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"), eve = player("Eve", "lobby");
        grant(alice, "networkchat.staff.send", "networkchat.staff.receive"); grant(bob, "networkchat.staff.receive");
        run("sc", alice); plugin.chat(new PlayerChatEvent(alice, "secret"));
        assertEquals(List.of("[Staff] [lobby] Alice: secret"), received.get(bob)); assertTrue(received.get(eve).isEmpty());
        run("sc", alice, "off"); plugin.chat(new PlayerChatEvent(alice, "public")); assertEquals(List.of("[lobby] Alice: public"), received.get(eve));
    }
    @Test void staffModeCannotEnableOnAnUnsafeSignedBackend() {
        Player alice = player("Alice", "lobby"), eve = player("Eve", "lobby"); modern(alice); grant(alice, "networkchat.staff.send", "networkchat.staff.receive");
        run("sc", alice, "on"); assertTrue(received.get(alice).get(0).startsWith("Staff mode needs safe chat capture"));
        PlayerChatEvent event = new PlayerChatEvent(alice, "ordinary"); plugin.chat(event); assertTrue(event.getResult().isAllowed()); assertTrue(received.get(eve).isEmpty());
    }
    @Test void staffModeBlocksUnsafeServerSwitchUntilTurnedOff() throws Exception {
        initialize(true, "chat.signed-backends=lobby\n");
        Player alice = player("Alice", "lobby"); modern(alice); grant(alice, "networkchat.staff.send", "networkchat.staff.receive"); run("sc", alice, "on");
        var destination = mock(com.velocitypowered.api.proxy.server.RegisteredServer.class);
        when(destination.getServerInfo()).thenReturn(new ServerInfo("unsafe", new java.net.InetSocketAddress("localhost", 25566)));
        var event = new com.velocitypowered.api.event.player.ServerPreConnectEvent(alice, destination); plugin.preConnect(event); assertFalse(event.getResult().isAllowed());
        run("sc", alice, "off"); event = new com.velocitypowered.api.event.player.ServerPreConnectEvent(alice, destination); plugin.preConnect(event); assertTrue(event.getResult().isAllowed());
    }
    @Test void staffModeFailsClosedAfterUnsafeReloadAndCanStillBeDisabled() throws Exception {
        initialize(true, "chat.signed-backends=lobby\n");
        Player alice = player("Alice", "lobby"), eve = player("Eve", "lobby"); modern(alice); grant(alice, "networkchat.staff.send", "networkchat.staff.receive", "networkchat.admin");
        run("sc", alice, "on"); Files.writeString(directory.resolve("config.properties"), "chat.capture=false\n"); run("nchat", alice, "reload");
        PlayerChatEvent event = new PlayerChatEvent(alice, "secret"); plugin.chat(event); assertFalse(event.getResult().isAllowed());
        verify(alice).disconnect(any(Component.class)); assertTrue(received.get(eve).isEmpty());
        permissions.get(alice).clear(); run("sc", alice, "off"); assertEquals("Staff chat: off.", received.get(alice).get(received.get(alice).size()-1));
    }
    @Test void earlierStaffPermissionRevocationNeverRoutesOrdinaryChatPublicly() {
        Player alice = player("Alice", "lobby"), eve = player("Eve", "lobby"); grant(alice, "networkchat.staff.send", "networkchat.staff.receive");
        run("sc", alice, "on"); permissions.get(alice).clear(); PlayerChatEvent event = new PlayerChatEvent(alice, "secret"); plugin.chat(event);
        assertFalse(event.getResult().isAllowed()); assertTrue(received.get(eve).isEmpty()); run("sc", alice); assertEquals("Staff chat: off.", received.get(alice).get(2));
    }
    @Test void legacyNormalChatCompletionsIncludeRemotePlayersAndFilterHiddenNames() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"), hidden = player("Bobby", "survival"); grant(hidden, "networkchat.hidden");
        var suggestions = new ArrayList<>(List.of("Bobby"));
        var event = new com.velocitypowered.api.event.player.TabCompleteEvent(alice, "hello B", suggestions); plugin.tabComplete(event); assertEquals(List.of("Bob"), event.getSuggestions());
        var backend = new ArrayList<>(List.of("backend")); plugin.tabComplete(new com.velocitypowered.api.event.player.TabCompleteEvent(alice, "/backend B", backend)); assertEquals(List.of("backend"), backend);
    }
    @Test void messageBodiesCompleteNetworkNamesAndHideRestrictedPlayers() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"), hidden = player("Bobby", "survival"); grant(alice, "networkchat.msg", "networkchat.staff.send", "networkchat.staff.receive"); grant(hidden, "networkchat.hidden");
        assertEquals(List.of("Bob"), commands.get("g").suggest(invocation(alice, "hello", "B")));
        assertEquals(List.of("Bob"), commands.get("sc").suggest(invocation(alice, "hello", "B")));
        assertEquals(List.of("Bob"), commands.get("msg").suggest(invocation(alice, "Bob", "hello", "B")));
    }
    @Test void delayedPlaceholderMessagesStayOrderedAndPermissionIsCheckedAtDelivery() throws Exception {
        initialize(false, "chat.cooldown-ms=0\nformat.global={prefix}{player}: {message}\n");
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival");
        var pending = new ArrayList<java.util.concurrent.CompletableFuture<String>>();
        plugin.usePlaceholderResolver((expression, id) -> { var future = new java.util.concurrent.CompletableFuture<String>(); pending.add(future); return future; });
        run("g", alice, "first"); run("g", alice, "second"); assertEquals(1, pending.size()); assertTrue(received.get(bob).isEmpty());
        pending.get(0).complete("&a[VIP] "); assertEquals(List.of("[VIP] Alice: first"), received.get(bob)); assertEquals(2, pending.size());
        pending.get(1).complete("&a[VIP] "); assertEquals("[VIP] Alice: second", received.get(bob).get(1));
        run("g", alice, "third");
        Files.writeString(directory.resolve("config.properties"), "permission.global-receive=receive.changed\n"); grant(alice, "networkchat.admin"); run("nchat", alice, "reload");
        pending.get(2).complete("[VIP] "); assertEquals(2, received.get(bob).size());
    }
    @Test void ignoringDuringPrivatePlaceholderLookupBlocksEventualDelivery() throws Exception {
        initialize(false, "private.cooldown-ms=0\nformat.private-received={prefix}{player}: {message}\n");
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival"); grant(alice, "networkchat.msg"); grant(bob, "networkchat.msg");
        var pending = new java.util.concurrent.CompletableFuture<String>(); plugin.usePlaceholderResolver((expression, id) -> pending);
        run("msg", alice, "Bob", "secret"); run("ignore", bob, "Alice"); pending.complete("[VIP] ");
        assertEquals(List.of("Alice: ignore on."), received.get(bob)); assertEquals(List.of("That player is unavailable."), received.get(alice));
    }
    @Test void aliasCollisionPreventsPartialInitialization() {
        when(manager.hasCommand("sc")).thenReturn(true); plugin = new NetworkChat(proxy, mock(Logger.class), directory); plugin.initialize(new ProxyInitializeEvent());
        Player alice = player("Alice", "lobby"); PlayerChatEvent event = new PlayerChatEvent(alice, "hello"); plugin.chat(event); assertTrue(event.getResult().isAllowed());
    }
}
