package dev.flint.networkchat;

import net.kyori.adventure.text.Component;
import java.util.*;
import java.util.concurrent.*;

/** Resolve only administrator-owned expressions, never user message text. */
final class MessageRenderer {
    private final PlaceholderResolver resolver;
    MessageRenderer(PlaceholderResolver resolver) { this.resolver = resolver; }
    CompletableFuture<Component> render(Settings config, String key, UUID sender, Map<String, String> input) {
        Map<String, Component> literal = new HashMap<>();
        input.forEach((token, value) -> literal.put(token, Component.text(value)));
        literal.put("prefix", Component.empty());
        CompletableFuture<Map<String, Component>> values = CompletableFuture.completedFuture(literal);
        for (String expression : config.placeholders(key)) {
            CompletableFuture<String> resolved;
            if (!config.bool("placeholders.enabled") || resolver == null) resolved = CompletableFuture.completedFuture(config.get("placeholders.fallback"));
            else {
                try {
                    // Timeout a dependent stage, never mutate a future owned by another plugin.
                    resolved = resolver.resolve(expression, sender).thenApply(value -> value)
                        .completeOnTimeout(config.get("placeholders.fallback"), config.number("placeholders.timeout-ms"), TimeUnit.MILLISECONDS)
                        .exceptionally(error -> config.get("placeholders.fallback"));
                } catch (RuntimeException error) { resolved = CompletableFuture.completedFuture(config.get("placeholders.fallback")); }
            }
            CompletableFuture<String> safe = resolved.thenApply(value -> value == null || value.equals(expression) || value.length() > 16384 ? config.get("placeholders.fallback") : value);
            values = values.thenCombine(safe, (map, value) -> {
                Map<String, Component> result = new HashMap<>(map);
                Component replacement;
                try { replacement = Text.expansion(value, config.get("placeholders.format")); }
                catch (RuntimeException error) { replacement = Text.expansion(config.get("placeholders.fallback"), config.get("placeholders.format")); }
                result.put(expression, replacement);
                if (expression.equals(config.get("placeholders.prefix"))) result.put("prefix", replacement);
                return result;
            });
        }
        return values.thenApply(resolved -> {
            if (config.bool("placeholders.inherit-name-color") && resolved.containsKey("player")) {
                var color = Text.rankColor(resolved.get("prefix"));
                if (color != null) resolved.put("player", resolved.get("player").color(color));
            }
            return Text.renderComponents(config.template(key), resolved);
        });
    }
}
