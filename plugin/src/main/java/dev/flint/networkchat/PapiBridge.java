package dev.flint.networkchat;

import net.william278.papiproxybridge.api.PlaceholderAPI;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Loaded only after Velocity has detected the optional dependency. */
final class PapiBridge implements PlaceholderResolver {
    private final PlaceholderAPI api = PlaceholderAPI.createInstance();
    @Override public CompletableFuture<String> resolve(String expression, UUID player) { return api.formatPlaceholders(expression, player); }
    @Override public void configure(Settings settings) {
        api.setCacheExpiry(settings.number("placeholders.cache-ms"));
        api.setRequestTimeout(settings.number("placeholders.timeout-ms"));
        api.setRetryTimes(0);
    }
}
