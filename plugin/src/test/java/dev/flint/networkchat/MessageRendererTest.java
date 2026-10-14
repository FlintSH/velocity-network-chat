package dev.flint.networkchat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageRendererTest {
    @TempDir Path directory;
    Settings config(String extra) throws Exception {
        Files.writeString(directory.resolve("config.properties"), extra); return Settings.load(directory);
    }
    String plain(Component text) { return PlainTextComponentSerializer.plainText().serialize(text); }
    @Test void resolvesSenderPrefixAndRawPapiExpressionsWithoutResolvingPlayerText() throws Exception {
        Settings config = config("format.global={prefix}{player}: {message} %player_name%\n");
        List<String> requests = new ArrayList<>(); UUID sender = UUID.randomUUID();
        MessageRenderer renderer = new MessageRenderer((expression, id) -> {
            assertEquals(sender, id); requests.add(expression); return CompletableFuture.completedFuture(expression.equals("%luckperms_prefix%") ? "§a[VIP] " : "Alice");
        });
        Component text = renderer.render(config, "format.global", sender, Map.of("player", "Alice", "message", "%player_name% &c<click:run_command:/op me>hello")).get(2, TimeUnit.SECONDS);
        assertEquals("[VIP] Alice: %player_name% &c<click:run_command:/op me>hello Alice", plain(text));
        assertEquals(Set.of("%luckperms_prefix%", "%player_name%"), Set.copyOf(requests));
    }
    @Test void miniMessageSupportsColorsGradientsAndSafeBuiltinTags() throws Exception {
        Settings config = config("format.mode=minimessage\nformat.global=<green><prefix><player></green><gray>: <message></gray>\nplaceholders.format=minimessage\n");
        MessageRenderer renderer = new MessageRenderer((expression, id) -> CompletableFuture.completedFuture("<gold>[VIP] </gold>"));
        Component text = renderer.render(config, "format.global", UUID.randomUUID(), Map.of("player", "Alice", "message", "<red>literal {player} %player_name%")).get();
        assertEquals("[VIP] Alice: <red>literal {player} %player_name%", plain(text));
        assertEquals("Gradient", plain(Text.parse("<gradient:red:blue>Gradient</gradient>", "minimessage")));
        Component green = Text.parse("<green>Color</green>", "minimessage"); assertEquals(NamedTextColor.GREEN, green.color());
    }
    @Test void absentDisabledUnresolvedAndFailedBridgeUseConfiguredFallback() throws Exception {
        Settings config = config("format.global={prefix}{message}\nplaceholders.fallback=[fallback] \n");
        for (PlaceholderResolver resolver : Arrays.asList(null,
            (PlaceholderResolver) (expression, id) -> CompletableFuture.failedFuture(new IllegalStateException()),
            (PlaceholderResolver) (expression, id) -> CompletableFuture.completedFuture(expression))) {
            assertEquals("[fallback]hello", plain(new MessageRenderer(resolver).render(config, "format.global", UUID.randomUUID(), Map.of("message", "hello")).get()));
        }
        Settings disabled = config("placeholders.enabled=false\nformat.global={prefix}{message}\n");
        assertEquals("hello", plain(new MessageRenderer((e, id) -> { fail("Disabled bridge was called"); return null; }).render(disabled, "format.global", UUID.randomUUID(), Map.of("message", "hello")).get()));
    }
    @Test void timesOutWithoutMutatingOrLateDeliveringTheBridgeOwnedFuture() throws Exception {
        Settings config = config("format.global={prefix}{message}\nplaceholders.timeout-ms=50\nplaceholders.fallback=[fallback]\n");
        CompletableFuture<String> owned = new CompletableFuture<>();
        CompletableFuture<Component> result = new MessageRenderer((e, id) -> owned).render(config, "format.global", UUID.randomUUID(), Map.of("message", "hello"));
        assertEquals("[fallback]hello", plain(result.get(2, TimeUnit.SECONDS))); assertFalse(owned.isDone()); owned.complete("late");
        assertEquals("[fallback]hello", plain(result.get()));
    }
    @Test void noPlaceholderTemplatesNeverCallBridge() throws Exception {
        Settings config = config("format.global={player}: {message}\n");
        Component text = new MessageRenderer((e, id) -> { fail("Unneeded request"); return null; }).render(config, "format.global", UUID.randomUUID(), Map.of("player", "Alice", "message", "hello")).get();
        assertEquals("Alice: hello", plain(text));
    }
    private String colored(Component component) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(component);
    }
    @Test void miniMessageRankColorOverridesWhiteUsernameButNotBracketsOrBody() throws Exception {
        Settings config = config("format.mode=minimessage\nformat.global=<prefix><white><player></white><gray>: <message></gray>\nplaceholders.format=minimessage\n");
        var renderer = new MessageRenderer((e, id) -> CompletableFuture.completedFuture("<white>[<red>Admin<white>] "));
        Component result = renderer.render(config, "format.global", UUID.randomUUID(), Map.of("player", "Alice", "message", "Hello")).get();
        assertEquals("[Admin] Alice: Hello", plain(result));
        String legacy = colored(result);
        assertTrue(legacy.contains("§cAlice"), legacy); assertTrue(legacy.contains("§f] "), legacy); assertTrue(legacy.contains("§7: Hello"), legacy);
    }
    @Test void legacyAndRawPapiPrefixesAlsoColorSenderNames() throws Exception {
        Settings config = config("format.global=%luckperms_prefix%&f{player}&7: {message}\n");
        Component result = new MessageRenderer((e, id) -> CompletableFuture.completedFuture("&f[&cAdmin&f] "))
            .render(config, "format.global", UUID.randomUUID(), Map.of("player", "Alice", "message", "Hello")).get();
        assertTrue(colored(result).contains("§cAlice"));
    }
    @Test void nameInheritanceCanBeDisabledAndUncoloredPrefixesKeepTemplateColor() throws Exception {
        Settings disabled = config("format.global={prefix}&f{player}\nplaceholders.inherit-name-color=false\n");
        Component result = new MessageRenderer((e, id) -> CompletableFuture.completedFuture("&c[Admin] "))
            .render(disabled, "format.global", UUID.randomUUID(), Map.of("player", "Alice")).get();
        assertTrue(colored(result).contains("§fAlice"));
        Settings plain = config("format.global={prefix}&f{player}\nplaceholders.format=plain\n");
        result = new MessageRenderer((e, id) -> CompletableFuture.completedFuture("[Admin] "))
            .render(plain, "format.global", UUID.randomUUID(), Map.of("player", "Alice")).get();
        assertNull(Text.rankColor(Text.expansion("[Admin] ", "plain"))); assertTrue(colored(result).contains("§fAlice"), colored(result));
    }
    @Test void nestedRgbRankStyleAndSymbolOnlyPrefixesAreRecognized() {
        var expected = net.kyori.adventure.text.format.TextColor.color(0x12ab34);
        Component nested = Component.empty().color(expected).append(Component.text("Admin")).append(Component.text("] ").color(NamedTextColor.WHITE));
        assertEquals(expected, Text.rankColor(nested));
        assertEquals(NamedTextColor.GOLD, Text.rankColor(Text.expansion("&f[&6★&f] ", "legacy")));
    }
}
