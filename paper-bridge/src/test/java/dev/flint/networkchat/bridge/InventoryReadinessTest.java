package dev.flint.networkchat.bridge;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

public class InventoryReadinessTest {
    public interface ReadyPlugin extends Plugin { boolean isReady(UUID player); }
    public interface WrongPlugin extends Plugin { String isReady(UUID player); }

    @Test void absenceIsOptionalUnlessRequired() {
        assertEquals(TeleportService.Readiness.READY, new InventoryReadiness(() -> null, () -> true, false).check(UUID.randomUUID()));
        assertEquals(TeleportService.Readiness.FAILED, new InventoryReadiness(() -> null, () -> true, true).check(UUID.randomUUID()));
    }

    @Test void requiresMainThreadEvenWithoutInventoryPlugin() {
        assertEquals(TeleportService.Readiness.FAILED, new InventoryReadiness(() -> null, () -> false, false).check(UUID.randomUUID()));
    }

    @Test void loadingIsDistinctFromDisabledOrBrokenApi() {
        for (boolean ready : new boolean[]{false, true}) {
            Plugin plugin = plugin(ReadyPlugin.class, true, ready);
            assertEquals(ready ? TeleportService.Readiness.READY : TeleportService.Readiness.LOADING,
                    new InventoryReadiness(() -> plugin, () -> true, false).check(UUID.randomUUID()));
        }
        for (Plugin plugin : new Plugin[]{plugin(ReadyPlugin.class, false, true), plugin(Plugin.class, true, true),
                plugin(WrongPlugin.class, true, "true"), plugin(ReadyPlugin.class, true, new IllegalStateException("failure"))}) {
            assertEquals(TeleportService.Readiness.FAILED,
                    new InventoryReadiness(() -> plugin, () -> true, false).check(UUID.randomUUID()));
        }
    }

    @Test void pluginReplacementDoesNotReuseOldMethod() {
        AtomicReference<Plugin> current = new AtomicReference<>(plugin(ReadyPlugin.class, true, true));
        InventoryReadiness integration = new InventoryReadiness(current::get, () -> true, false);
        assertEquals(TeleportService.Readiness.READY, integration.check(UUID.randomUUID()));
        current.set(plugin(Plugin.class, true, true));
        assertEquals(TeleportService.Readiness.FAILED, integration.check(UUID.randomUUID()));
        current.set(plugin(ReadyPlugin.class, true, true));
        assertEquals(TeleportService.Readiness.READY, integration.check(UUID.randomUUID()));
    }

    private Plugin plugin(Class<?> type, boolean enabled, Object ready) {
        return (Plugin) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("isEnabled")) return enabled;
            if (method.getName().equals("isReady")) {
                if (ready instanceof RuntimeException e) throw e;
                return ready;
            }
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return null;
        });
    }
}
