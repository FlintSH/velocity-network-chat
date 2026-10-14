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
}
