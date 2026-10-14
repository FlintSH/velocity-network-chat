package dev.flint.networkchat;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Small boundary so the optional bridge is never linked when it is absent. */
@FunctionalInterface
interface PlaceholderResolver {
    CompletableFuture<String> resolve(String expression, UUID player);
    default void configure(Settings settings) { }
}
