package dev.flint.networkchat.bridge;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;

/** Optional integration without binding either plugin to the other's class loader. */
final class InventoryReadiness {
    private final Supplier<Plugin> lookup;
    private final BooleanSupplier mainThread;
    private final boolean required;
    private Plugin cached;
    private Method readyMethod;

    InventoryReadiness(Supplier<Plugin> lookup, BooleanSupplier mainThread, boolean required) {
        this.lookup = lookup;
        this.mainThread = mainThread;
        this.required = required;
    }

    TeleportService.Readiness check(UUID player) {
        if (!mainThread.getAsBoolean()) return TeleportService.Readiness.FAILED;
        Plugin plugin = lookup.get();
        if (plugin == null) return required ? TeleportService.Readiness.FAILED : TeleportService.Readiness.READY;
        if (!plugin.isEnabled()) return TeleportService.Readiness.FAILED;
        if (plugin != cached) {
            cached = plugin;
            readyMethod = null;
            try {
                Method method = plugin.getClass().getMethod("isReady", UUID.class);
                if (method.getReturnType() == boolean.class) readyMethod = method;
            } catch (ReflectiveOperationException | SecurityException ignored) { }
        }
        if (readyMethod == null) return TeleportService.Readiness.FAILED;
        try {
            return Boolean.TRUE.equals(readyMethod.invoke(plugin, player))
                    ? TeleportService.Readiness.READY : TeleportService.Readiness.LOADING;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return TeleportService.Readiness.FAILED;
        }
    }
}
