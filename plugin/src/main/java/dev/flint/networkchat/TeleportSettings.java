package dev.flint.networkchat;

import net.kyori.adventure.text.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Restart-only settings: an in-flight transfer must keep the same trust and routing policy. */
final class TeleportSettings {
    static final List<String> COMMANDS = List.of("request", "accept", "deny", "cancel", "staff");
    private final Map<String, String> values;
    private final Map<String, List<String>> aliases = new LinkedHashMap<>();
    private final Map<String, Component> messages = new HashMap<>();
    private final Set<String> servers;

    private TeleportSettings(Properties properties) {
        Map<String, String> copy = new HashMap<>();
        properties.forEach((k, v) -> copy.put(k.toString(), v.toString().trim()));
        values = Map.copyOf(copy);
        for (String key : List.of("enabled", "respect-chat-groups"))
            if (!Set.of("true", "false").contains(get(key))) throw new IllegalArgumentException("Teleport " + key + " must be true/false");
        range("request-seconds", 5, 300); range("cooldown-seconds", 0, 300);
        range("transfer-seconds", 5, 30); range("max-incoming", 1, 100); range("max-transfers", 1, 10000);
        if (!get("shared-secret").isEmpty()) dev.flint.networkchat.teleport.TeleportProtocol.validateSecret(get("shared-secret"));
        for (String key : List.of("permission.staff", "permission.bypass-cooldown"))
            if (get(key).isEmpty()) throw new IllegalArgumentException("Privileged teleport permission cannot be empty: " + key);
        if (!Set.of("legacy", "minimessage", "plain").contains(get("message-mode")))
            throw new IllegalArgumentException("Invalid teleport message-mode");
        Set<String> used = new HashSet<>();
        for (String kind : COMMANDS) {
            List<String> names = Arrays.stream(get("command." + kind).split(",", -1)).map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).toList();
            for (String name : names) if (!name.matches("[a-z][a-z0-9_-]*") || !used.add(name))
                throw new IllegalArgumentException("Invalid/duplicate teleport alias: " + name);
            aliases.put(kind, names);
        }
        servers = Set.copyOf(Arrays.stream(get("servers").split(",")).map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList());
        values.forEach((k, v) -> { if (k.startsWith("message.")) messages.put(k.substring(8), Text.parse(v, get("message-mode"))); });
    }

    static TeleportSettings load(Path directory) throws IOException {
        Properties defaults = new Properties();
        try (var input = TeleportSettings.class.getResourceAsStream("/teleport.properties")) {
            if (input == null) throw new IOException("Missing teleport defaults");
            defaults.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        Files.createDirectories(directory);
        Path file = directory.resolve("teleport.properties");
        if (Files.notExists(file)) try (var input = TeleportSettings.class.getResourceAsStream("/teleport.properties")) { Files.copy(input, file); }
        Properties configured = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { configured.load(reader); }
        for (String key : configured.stringPropertyNames()) {
            if (!defaults.containsKey(key)) throw new IllegalArgumentException("Unknown teleport setting: " + key);
            defaults.setProperty(key, configured.getProperty(key));
        }
        return new TeleportSettings(defaults);
    }

    String get(String key) { return values.getOrDefault(key, ""); }
    int number(String key) { return Integer.parseInt(get(key)); }
    boolean bool(String key) { return Boolean.parseBoolean(get(key)); }
    boolean configured() { return bool("enabled") && get("shared-secret").length() >= 32; }
    boolean serverAllowed(String server) { return !server.isEmpty() && (servers.contains("*") || servers.contains(server.toLowerCase(Locale.ROOT))); }
    List<String> aliases(String kind) { return aliases.get(kind); }
    Component message(String key, Map<String, String> parameters) { return Text.render(messages.get(key), parameters); }
    private void range(String key, int min, int max) {
        int value = number(key);
        if (value < min || value > max) throw new IllegalArgumentException("Teleport " + key + " must be " + min + "–" + max);
    }
}
