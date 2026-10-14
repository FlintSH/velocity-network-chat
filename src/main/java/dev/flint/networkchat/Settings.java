package dev.flint.networkchat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.Component;

/** Immutable, validated snapshot. A failed reload never replaces the active snapshot. */
final class Settings {
    private final Map<String, String> values;
    private final Map<String, Set<String>> lists;
    private final Map<String, Component> templates;
    private Settings(Map<String, String> values) {
        this.values = Map.copyOf(values);
        Map<String, Set<String>> parsedLists = new HashMap<>();
        Map<String, Component> parsedTemplates = new HashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith("command.") || key.equals("chat.signed-backends") || key.equals("chat.excluded-servers")) {
                Set<String> list = new LinkedHashSet<>();
                for (String item : value.split(",")) if (!item.isBlank()) list.add(item.trim().toLowerCase(Locale.ROOT));
                parsedLists.put(key, Collections.unmodifiableSet(list));
            }
            if (key.startsWith("format.") || key.startsWith("message.")) parsedTemplates.put(key, Text.parse(value));
        });
        lists = Map.copyOf(parsedLists); templates = Map.copyOf(parsedTemplates);
    }
    Component render(String key, Map<String, String> values) { return Text.render(templates.get(key), values); }
    static Settings load(Path directory) throws IOException {
        Properties defaults = new Properties();
        try (var stream = Settings.class.getResourceAsStream("/config.properties")) {
            if (stream == null) throw new IOException("Missing bundled configuration");
            defaults.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        Files.createDirectories(directory);
        Path file = directory.resolve("config.properties");
        if (Files.notExists(file)) {
            try (var stream = Settings.class.getResourceAsStream("/config.properties")) { Files.copy(stream, file); }
        }
        Properties configured = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { configured.load(reader); }
        Map<String, String> values = new HashMap<>();
        defaults.forEach((k, v) -> values.put(k.toString(), v.toString()));
        configured.forEach((k, v) -> {
            String key = k.toString();
            if (!defaults.containsKey(key) && !key.startsWith("routing.group.") && !key.startsWith("display.server."))
                throw new IllegalArgumentException("Unknown setting: " + key);
            values.put(key, v.toString().trim());
        });
        Settings result = new Settings(values);
        result.validate();
        return result;
    }
    String get(String key) { return values.getOrDefault(key, ""); }
    boolean bool(String key) { return Boolean.parseBoolean(get(key)); }
    int number(String key) { return Integer.parseInt(get(key)); }
    Set<String> list(String key) { return lists.getOrDefault(key, Set.of()); }
    String group(String server) { return values.getOrDefault("routing.group." + server.toLowerCase(Locale.ROOT), get("routing.default-group")); }
    String label(String server) { return values.getOrDefault("display.server." + server.toLowerCase(Locale.ROOT), server); }
    boolean excluded(String server) { return list("chat.excluded-servers").contains(server.toLowerCase(Locale.ROOT)); }
    boolean signedBackend(String server) { var servers = list("chat.signed-backends"); return servers.contains("*") || servers.contains(server.toLowerCase(Locale.ROOT)); }
    private void validate() {
        if (number("config-version") != 1) throw new IllegalArgumentException("Unsupported config-version");
        for (String key : List.of("chat.capture", "private.enabled", "private.allow-self", "private.respect-groups"))
            if (!Set.of("true", "false").contains(get(key))) throw new IllegalArgumentException("Expected true/false: " + key);
        for (String key : List.of("chat.cooldown-ms", "staff.cooldown-ms", "private.cooldown-ms"))
            if (number(key) < 0 || number(key) > 60000) throw new IllegalArgumentException("Cooldown must be 0–60000: " + key);
        if (number("chat.max-length") < 1 || number("chat.max-length") > 4096) throw new IllegalArgumentException("chat.max-length must be 1–4096");
        if (number("suggestions.limit") < 1 || number("suggestions.limit") > 500) throw new IllegalArgumentException("suggestions.limit must be 1–500");
        if (!Set.of("global", "local").contains(get("chat.default-channel"))) throw new IllegalArgumentException("Default channel must be global or local");
        for (String key : List.of("permission.staff-send", "permission.staff-receive", "permission.admin", "permission.hidden", "permission.see-hidden", "permission.bypass-cooldown"))
            if (get(key).isBlank()) throw new IllegalArgumentException("Privileged permission must not be empty: " + key);
        Set<String> aliases = new HashSet<>();
        for (String kind : List.of("global", "staff", "message", "reply", "ignore", "toggle", "channel", "admin")) {
            if (list("command." + kind).isEmpty()) throw new IllegalArgumentException("Empty command: " + kind);
            for (String alias : get("command." + kind).split(",")) {
                alias = alias.trim().toLowerCase(Locale.ROOT);
                if (!alias.matches("[a-z][a-z0-9_-]*") || !aliases.add(alias)) throw new IllegalArgumentException("Invalid/duplicate command alias: " + alias);
            }
        }
    }
}
