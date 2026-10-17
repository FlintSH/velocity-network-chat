package dev.flint.networkchat;

import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.server.*;
import dev.flint.networkchat.teleport.TeleportProtocol;
import dev.flint.networkchat.teleport.TeleportProtocol.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TeleportServiceTest {
    static final String SECRET = "a-test-secret-which-is-at-least-32-characters";
    @TempDir Path directory;
    ProxyServer proxy;
    TeleportService service;
    Settings chat;
    final AtomicLong clock = new AtomicLong(1_000_000);
    final Map<UUID, Player> players = new HashMap<>();
    final Map<String, RegisteredServer> servers = new HashMap<>();
    final Map<Player, Set<String>> permissions = new HashMap<>();
    final Map<Player, List<String>> messages = new HashMap<>();
    final Map<Player, ServerConnection> connections = new HashMap<>();
    final List<Sent> sent = new ArrayList<>();
    record Sent(ServerConnection carrier, Packet packet) { }
    @BeforeEach void setup() throws Exception {
        proxy = mock(ProxyServer.class);
        when(proxy.getAllPlayers()).thenAnswer(i -> List.copyOf(players.values()));
        when(proxy.getPlayer(any(UUID.class))).thenAnswer(i -> Optional.ofNullable(players.get(i.getArgument(0))));
        when(proxy.getPlayer(anyString())).thenAnswer(i -> players.values().stream().filter(p -> p.getUsername().equalsIgnoreCase(i.getArgument(0))).findFirst());
        when(proxy.getServer(anyString())).thenAnswer(i -> Optional.ofNullable(servers.get(i.getArgument(0))));
        when(proxy.getChannelRegistrar()).thenReturn(mock(ChannelRegistrar.class));
        when(proxy.getCommandManager()).thenReturn(mock(CommandManager.class));
        chat = Settings.load(directory);
        configure("");
    }
    void configure(String extra) throws Exception {
        Files.writeString(directory.resolve("teleport.properties"), "shared-secret=" + SECRET + "\n" + extra);
        service = new TeleportService(this, proxy, TeleportSettings.load(directory), () -> chat, (a,b) -> true,
            clock::get, () -> TimeUnit.MILLISECONDS.toNanos(clock.get()));
    }
    Player player(String name, String server) {
        Player p = mock(Player.class); UUID id = UUID.randomUUID();
        when(p.getUniqueId()).thenReturn(id); when(p.getUsername()).thenReturn(name); when(p.isActive()).thenReturn(true);
        players.put(id, p); permissions.put(p, new HashSet<>()); messages.put(p, new ArrayList<>());
        when(p.hasPermission(anyString())).thenAnswer(i -> permissions.get(p).contains(i.getArgument(0)));
        when(p.getCurrentServer()).thenAnswer(i -> Optional.ofNullable(connections.get(p)));
        doAnswer(i -> { messages.get(p).add(PlainTextComponentSerializer.plainText().serialize((Component)i.getArgument(0))); return null; }).when(p).sendMessage(any(Component.class));
        connect(p, server); return p;
    }
    void connect(Player player, String server) {
        RegisteredServer registered = servers.computeIfAbsent(server, s -> {
            RegisteredServer r = mock(RegisteredServer.class);
            when(r.getServerInfo()).thenReturn(new ServerInfo(s, new InetSocketAddress("127.0.0.1", 25565))); return r;
        });
        ServerConnection connection = mock(ServerConnection.class);
        ServerInfo information = registered.getServerInfo();
        when(connection.getServer()).thenReturn(registered); when(connection.getServerInfo()).thenReturn(information);
        when(connection.getPlayer()).thenReturn(player);
        when(connection.sendPluginMessage(eq(TeleportService.CHANNEL), any(byte[].class))).thenAnswer(i -> {
            sent.add(new Sent(connection, TeleportProtocol.decode(i.getArgument(1), SECRET, clock.get()))); return true;
        });
        connections.put(player, connection);
    }
    void run(String kind, Player player, String... args) { service.executeCommand(kind, player, args); }
    Sent latest() { return sent.get(sent.size()-1); }
    PluginMessageEvent response(Sent request, Type type) {
        Packet p = request.packet;
        Packet result = new Packet(type, p.operation(), p.nonce(), p.mover(), p.target(), clock.get(), p.expiresAt());
        PluginMessageEvent event = new PluginMessageEvent(request.carrier, request.carrier.getPlayer(), TeleportService.CHANNEL, TeleportProtocol.encode(result, SECRET));
        service.message(event); return event;
    }
    void request(Player mover, Player target) { run("request", mover, target.getUsername()); run("accept", target); }
    boolean saw(Player player, String text) { return messages.get(player).stream().anyMatch(s -> s.contains(text)); }
    CompletableFuture<ConnectionRequestBuilder.Result> connectionFuture(Player mover) {
        ConnectionRequestBuilder builder = mock(ConnectionRequestBuilder.class);
        CompletableFuture<ConnectionRequestBuilder.Result> future = new CompletableFuture<>();
        when(mover.createConnectionRequest(any())).thenReturn(builder); when(builder.connect()).thenReturn(future); return future;
    }
    void complete(CompletableFuture<ConnectionRequestBuilder.Result> future, boolean success) {
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(result.isSuccessful()).thenReturn(success); future.complete(result);
    }

    @Test void crossServerRequiresConsentAndBridgeReadinessAndConfirmsActualTeleport() {
        Player alice = player("Alice", "lobby"), bob = player("Bob", "survival");
        var future = connectionFuture(alice);
        run("request", alice, "Bob"); assertTrue(sent.isEmpty());
        assertTrue(saw(bob, "/tpaccept Alice"));
        run("accept", bob); assertEquals(Type.PROBE, latest().packet.type());
        verify(alice, never()).createConnectionRequest(any());
        response(latest(), Type.READY); verify(alice).createConnectionRequest(servers.get("survival"));
        assertFalse(saw(alice, "Teleported to"));
        connect(alice, "survival"); complete(future, true);
        assertEquals(Type.EXECUTE, latest().packet.type());
        response(latest(), Type.WAIT); assertFalse(saw(alice, "Teleported to"));
        clock.addAndGet(1001); service.tick();
        response(latest(), Type.SUCCESS); assertTrue(saw(alice, "Teleported to Bob"));
        int count = sent.size(); clock.addAndGet(1001); service.tick(); assertEquals(count, sent.size());
    }
    @Test void sameServerStillRequiresBridgeAndActualSuccess() {
        Player a = player("Alice", "lobby"), b = player("Bob", "lobby");
        request(a,b); response(latest(), Type.READY); assertEquals(Type.EXECUTE, latest().packet.type());
        verify(a, never()).createConnectionRequest(any()); response(latest(), Type.SUCCESS);
        assertTrue(saw(a, "Teleported to Bob"));
    }
    @Test void missingBridgeNeverSwitchesServer() {
        Player a = player("Alice", "lobby"), b = player("Bob", "survival");
        request(a,b); clock.addAndGet(15_001); service.tick();
        verify(a, never()).createConnectionRequest(any()); assertTrue(saw(a, "bridge did not respond"));
    }
    @Test void staffTpIsPermissionLockedAndDoesNotNeedConsent() {
        Player a = player("Alice", "lobby"), b = player("Bob", "survival");
        run("staff", a, "Bob"); assertTrue(saw(a, "permission")); assertTrue(sent.isEmpty());
        permissions.get(a).add("networkchat.teleport.staff"); run("staff", a, "Bob");
        assertEquals(Type.PROBE, latest().packet.type()); assertFalse(saw(b, "wants to teleport"));
    }
    @Test void clientsCannotForwardEvenWellSignedResponses() {
        Player a = player("Alice", "lobby"), b = player("Bob", "survival"); request(a,b);
        Packet p = latest().packet;
        var event = new PluginMessageEvent(a, connections.get(a), TeleportService.CHANNEL,
            TeleportProtocol.encode(new Packet(Type.READY,p.operation(),p.nonce(),p.mover(),p.target(),clock.get(),p.expiresAt()),SECRET));
        service.message(event); assertFalse(event.getResult().isAllowed()); verify(a,never()).createConnectionRequest(any());
    }
    @Test void invalidMacAndWrongBackendAndUnexpectedSuccessCannotAdvanceTransfer() {
        Player a = player("Alice", "lobby"), b = player("Bob", "survival"); request(a,b);
        Sent request = latest(); Packet p = request.packet;
        byte[] bytes = TeleportProtocol.encode(new Packet(Type.READY,p.operation(),p.nonce(),p.mover(),p.target(),clock.get(),p.expiresAt()), SECRET);
        service.message(new PluginMessageEvent(connections.get(a), a, TeleportService.CHANNEL, bytes));
        bytes[10] ^= 1; service.message(new PluginMessageEvent(request.carrier,b,TeleportService.CHANNEL,bytes));
        response(request, Type.SUCCESS); verify(a,never()).createConnectionRequest(any()); assertFalse(saw(a,"Teleported to"));
    }
    @Test void targetSwitchDisconnectOrPermissionRevocationCancelsTransfer() {
        Player a = player("Alice", "lobby"), b = player("Bob", "survival"); request(a,b);
        Sent request = latest(); connect(b,"creative"); response(request,Type.READY);
        verify(a,never()).createConnectionRequest(any()); assertTrue(saw(a,"failed"));
    }
    @Test void lateConnectionCallbackAfterTimeoutNeverSendsExecute() {
        Player a = player("Alice", "lobby"), b = player("Bob", "survival"); var future=connectionFuture(a);
        request(a,b); response(latest(),Type.READY); clock.addAndGet(15_001); service.tick();
        connect(a,"survival"); complete(future,true);
        assertTrue(sent.stream().noneMatch(s -> s.packet.type()==Type.EXECUTE)); assertTrue(saw(a,"timed out"));
    }
    @Test void refusedConnectionDoesNotReportSuccessOrExecute() {
        Player a = player("Alice", "lobby"), b = player("Bob", "survival"); var future=connectionFuture(a);
        request(a,b); response(latest(),Type.READY); complete(future,false);
        assertTrue(sent.stream().noneMatch(s -> s.packet.type()==Type.EXECUTE)); assertTrue(saw(a,"failed")); assertFalse(saw(a,"Teleported to"));
    }
    @Test void requestExpiryDenialCancelAndCooldown() {
        Player a=player("Alice","lobby"),b=player("Bob","survival");
        run("request",a,"Bob"); run("deny",b); assertTrue(saw(a,"declined"));
        run("request",a,"Bob"); assertTrue(saw(a,"Please wait")); clock.addAndGet(10_001);
        run("request",a,"Bob"); run("cancel",a); assertTrue(saw(a,"cancelled")); clock.addAndGet(10_001);
        run("request",a,"Bob"); clock.addAndGet(60_001); run("accept",b);
        assertTrue(saw(a,"expired")); assertTrue(sent.isEmpty());
    }
    @Test void multipleRequestsRequireExplicitSelectionAndAreBounded() throws Exception {
        configure("max-incoming=2\n");
        Player a=player("Alice","lobby"),b=player("Bob","survival"),c=player("Carol","lobby"),d=player("Dave","lobby");
        run("request",a,"Bob");run("request",c,"Bob");run("request",d,"Bob");run("accept",b);
        assertTrue(saw(d,"Too many"));assertTrue(saw(b,"Several"));assertTrue(sent.isEmpty());
        run("accept",b,"Carol");assertEquals(c.getUniqueId(),latest().packet.mover());
    }
    @Test void hiddenPlayersAndGroupsAreRespectedInCommandsAndSuggestions() throws Exception {
        configure("respect-chat-groups=true\n");
        Files.writeString(directory.resolve("config.properties"),"routing.group.creative=creative\n");chat=Settings.load(directory);
        Player a=player("Alice","lobby"),b=player("Bob","survival"),c=player("Carol","creative");
        permissions.get(b).add("networkchat.hidden");
        assertEquals(List.of(),service.suggestions("request",a,new String[]{""}));
        run("request",a,"Bob");run("request",a,"Carol");assertTrue(sent.isEmpty());
        permissions.get(a).add("networkchat.see.hidden");assertEquals(List.of("Bob"),service.suggestions("request",a,new String[]{""}));
    }
    @Test void permissionRevocationBeforeAcceptAndDuringTransferIsHonored() throws Exception {
        configure("permission.request=networkchat.tpa\n");
        Player a=player("Alice","lobby"),b=player("Bob","survival");
        permissions.get(a).add("networkchat.tpa");permissions.get(b).add("networkchat.tpa");
        run("request",a,"Bob"); permissions.get(a).clear(); run("accept",b); assertTrue(sent.isEmpty());
        permissions.get(a).addAll(List.of("networkchat.tpa","networkchat.teleport.bypass-cooldown"));
        request(a,b);Sent probe=latest();permissions.get(a).clear();response(probe,Type.READY);
        verify(a,never()).createConnectionRequest(any());
    }
    @Test void duplicateDisconnectDoesNotCancelActivePlayersRequest() {
        Player a=player("Alice","lobby"),b=player("Bob","survival");run("request",a,"Bob");
        UUID originalId=a.getUniqueId();Player duplicate=mock(Player.class);when(duplicate.getUniqueId()).thenReturn(originalId);
        service.disconnect(duplicate);run("accept",b);assertEquals(Type.PROBE,latest().packet.type());
    }
    @Test void reconnectWithSameUuidCannotUseOldConsentOrAcknowledgment() {
        Player a=player("Alice","lobby"),b=player("Bob","survival");request(a,b);Sent probe=latest();
        Player replacement=mock(Player.class);players.put(a.getUniqueId(),replacement);response(probe,Type.READY);
        verify(a,never()).createConnectionRequest(any());
    }
    @Test void disconnectedTargetClearsPendingRequest() {
        Player a=player("Alice","lobby"),b=player("Bob","survival");run("request",a,"Bob");
        service.disconnect(b);run("accept",b);assertTrue(sent.isEmpty());assertTrue(saw(b,"No matching"));
    }
    @Test void staleNonceCannotAuthorizeANewerAttempt() {
        Player a=player("Alice","lobby"),b=player("Bob","survival");request(a,b);Sent first=latest();
        clock.addAndGet(1001);service.tick();response(first,Type.READY);verify(a,never()).createConnectionRequest(any());
    }
    @Test void executePacketCannotOutliveOperationDeadline() {
        Player a=player("Alice","lobby"),b=player("Bob","survival");var future=connectionFuture(a);
        request(a,b);response(latest(),Type.READY);clock.addAndGet(14_000);connect(a,"survival");complete(future,true);
        assertEquals(1_015_000,latest().packet.expiresAt());
    }
    @Test void temporarilyMissingBackendDuringConnectionDoesNotCancelTheTransfer() {
        Player a=player("Alice","lobby"),b=player("Bob","survival");var future=connectionFuture(a);
        request(a,b);response(latest(),Type.READY);connections.remove(a);clock.addAndGet(500);service.tick();
        assertFalse(saw(a,"failed"));connect(a,"survival");complete(future,true);
        assertEquals(Type.EXECUTE,latest().packet.type());response(latest(),Type.SUCCESS);assertTrue(saw(a,"Teleported to"));
    }
    @Test void permissionRevocationSendsSignedCancellationForInFlightWork() throws Exception {
        configure("permission.request=networkchat.tpa\n");
        Player a=player("Alice","lobby"),b=player("Bob","lobby");
        permissions.get(a).add("networkchat.tpa");permissions.get(b).add("networkchat.tpa");
        request(a,b);response(latest(),Type.READY);Sent execution=latest();
        permissions.get(a).clear();service.tick();
        assertEquals(Type.CANCEL,latest().packet.type());assertEquals(execution.packet.operation(),latest().packet.operation());
        response(execution,Type.SUCCESS);assertFalse(saw(a,"Teleported to"));
    }
    @Test void disabledSetupDoesNotBreakChatAndRejectsUnsafeConfiguration() throws Exception {
        configure("shared-secret=\n");Player a=player("Alice","lobby");run("request",a,"Bob");assertTrue(saw(a,"not configured"));
        assertThrows(IllegalArgumentException.class,()->configure("permission.staff=\n"));
        assertThrows(IllegalArgumentException.class,()->configure("shared-secret=weak\n"));
        assertThrows(IllegalArgumentException.class,()->configure("command.staff=tpa\n"));
    }
    @Test void chatAliasCollisionsFailBeforeRegisteringAnything() {
        assertThrows(IllegalArgumentException.class,()->service.validateAliases(List.of("tpa")));
        verify(proxy.getCommandManager(),never()).register(any(CommandMeta.class),any(Command.class));
    }
}
