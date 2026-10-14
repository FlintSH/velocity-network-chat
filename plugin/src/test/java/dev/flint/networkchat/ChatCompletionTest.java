package dev.flint.networkchat;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.*;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.util.GameProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatCompletionTest {
    public interface CustomPlayer extends Player {
        void addCustomChatCompletions(Collection<String> names);
        void removeCustomChatCompletions(Collection<String> names);
    }
    @TempDir Path directory;
    Settings config(String extra) throws Exception { Files.writeString(directory.resolve("config.properties"), extra); return Settings.load(directory); }
    Player player(String name, String backend, int protocol) {
        Player player = mock(CustomPlayer.class); when(player.getUsername()).thenReturn(name); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isActive()).thenReturn(true);
        var version = mock(ProtocolVersion.class); when(version.getProtocol()).thenReturn(protocol); when(player.getProtocolVersion()).thenReturn(version);
        var connection = mock(ServerConnection.class); when(connection.getServerInfo()).thenReturn(new ServerInfo(backend, new InetSocketAddress("localhost", 25565))); when(player.getCurrentServer()).thenReturn(Optional.of(connection)); return player;
    }
    Map<UUID, TabListEntry> tab(Player player) {
        TabList tab = mock(TabList.class); when(player.getTabList()).thenReturn(tab); Map<UUID, TabListEntry> entries = new HashMap<>();
        when(tab.containsEntry(any(UUID.class))).thenAnswer(i -> entries.containsKey(i.getArgument(0)));
        when(tab.buildEntry(any(GameProfile.class), isNull(), eq(0), eq(0))).thenAnswer(i -> {
            TabListEntry entry = mock(TabListEntry.class); when(entry.getProfile()).thenReturn(i.getArgument(0)); return entry;
        });
        doAnswer(i -> { TabListEntry entry = i.getArgument(0); entries.put(entry.getProfile().getId(), entry); return null; }).when(tab).addEntry(any());
        when(tab.removeEntry(any(UUID.class))).thenAnswer(i -> Optional.ofNullable(entries.remove(i.getArgument(0)))); return entries;
    }
    @Test void modernSuggestionsUseDeltasAndNeverChangeTabEntries() throws Exception {
        ChatCompletion service = new ChatCompletion(mock(Logger.class), CustomPlayer.class);
        Player alice = player("Alice", "lobby", 770), bob = player("Bob", "survival", 770); Map<UUID, TabListEntry> entries = tab(alice);
        service.refresh(config(""), List.of(alice, bob), (viewer, target) -> true);
        verify((CustomPlayer) alice).addCustomChatCompletions(Set.of("Alice", "Bob")); assertTrue(entries.isEmpty());
        service.refresh(config(""), List.of(alice, bob), (viewer, target) -> target != bob);
        verify((CustomPlayer) alice).removeCustomChatCompletions(Set.of("Bob"));
        service.refresh(config("suggestions.chat=false\n"), List.of(alice, bob), (viewer, target) -> true);
        verify((CustomPlayer) alice).removeCustomChatCompletions(Set.of("Alice"));
    }
    @Test void intermediateClientsGetOnlyOwnedRemoteEntriesAndCleanupPreservesBackendEntries() throws Exception {
        ChatCompletion service = new ChatCompletion(mock(Logger.class), CustomPlayer.class);
        Player alice = player("Alice", "lobby", 754), bob = player("Bob", "survival", 754), local = player("Local", "lobby", 754);
        Map<UUID, TabListEntry> entries = tab(alice);
        TabListEntry backend = mock(TabListEntry.class); entries.put(local.getUniqueId(), backend);
        service.refresh(config(""), List.of(alice, bob, local), (viewer, target) -> true);
        UUID owned = ChatCompletion.ownedId(bob.getUniqueId()); assertNotEquals(bob.getUniqueId(), owned);
        assertEquals("Bob", entries.get(owned).getProfile().getName()); assertEquals(2, entries.size());
        service.refresh(config(""), List.of(alice, bob, local), (viewer, target) -> target != bob);
        assertEquals(Map.of(local.getUniqueId(), backend), entries);
        service.refresh(config("suggestions.legacy-tab-list=false\n"), List.of(alice, bob, local), (viewer, target) -> true);
        assertEquals(Map.of(local.getUniqueId(), backend), entries);
    }
    @Test void serverSwitchResendsModernNamesAndLegacyEntriesAreRestoredIfBackendClearsThem() throws Exception {
        ChatCompletion service = new ChatCompletion(mock(Logger.class), CustomPlayer.class);
        Player modern = player("Modern", "lobby", 770), old = player("Old", "survival", 754); Map<UUID, TabListEntry> entries = tab(old);
        Settings config = config(""); service.refresh(config, List.of(modern, old), (v, t) -> true);
        service.backendChanged(modern); entries.clear(); service.refresh(config, List.of(modern, old), (v, t) -> true);
        verify((CustomPlayer) modern, times(2)).addCustomChatCompletions(Set.of("Modern", "Old")); assertEquals(1, entries.size());
    }
    @Test void legacyTabFallbackIsBounded() throws Exception {
        ChatCompletion service = new ChatCompletion(mock(Logger.class), CustomPlayer.class);
        Player alice = player("Alice", "lobby", 754), bob = player("Bob", "survival", 754), chris = player("Chris", "survival", 754); Map<UUID, TabListEntry> entries = tab(alice);
        service.refresh(config("suggestions.legacy-tab-limit=1\n"), List.of(alice, bob, chris), (v, t) -> true); assertEquals(1, entries.size());
    }
    @Test void productionCatalogChecksVisibilityOncePerPlayerAndSkipsUnchangedPackets() throws Exception {
        ChatCompletion service = new ChatCompletion(mock(Logger.class), CustomPlayer.class);
        Player alice = player("Alice", "lobby", 770), bob = player("Bob", "survival", 770);
        var hiddenChecks = new java.util.concurrent.atomic.AtomicInteger();
        var visibility = (java.util.function.Predicate<Player>) p -> { hiddenChecks.incrementAndGet(); return p == bob; };
        Settings config = config("");
        service.refresh(config, List.of(alice, bob), visibility, p -> false);
        verify((CustomPlayer) alice).addCustomChatCompletions(Set.of("Alice"));
        verify((CustomPlayer) bob).addCustomChatCompletions(Set.of("Alice", "Bob"));
        service.refresh(config, List.of(alice, bob), visibility, p -> false);
        assertEquals(4, hiddenChecks.get()); verify((CustomPlayer) alice, times(1)).addCustomChatCompletions(any());
        service.refresh(config, List.of(alice, bob), p -> false, p -> false);
        verify((CustomPlayer) alice).addCustomChatCompletions(Set.of("Bob"));
    }
}
