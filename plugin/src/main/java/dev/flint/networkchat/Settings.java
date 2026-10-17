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
    private final Map<String, Set<String>> placeholders;
    private Settings(Map<String, String> values) {
        this.values = Map.copyOf(values);
        Map<String, Set<String>> parsedLists = new HashMap<>();
        Map<String, Component> parsedTemplates = new HashMap<>();
        Map<String, Set<String>> parsedPlaceholders = new HashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith("command.") || key.equals("chat.signed-backends") || key.equals("chat.excluded-servers")) {
                Set<String> list = new LinkedHashSet<>();
                for (String item : value.split(",")) if (!item.isBlank()) list.add(item.trim().toLowerCase(Locale.ROOT));
                parsedLists.put(key, Collections.unmodifiableSet(list));
            }
            if ((key.startsWith("format.") || key.startsWith("message.")) && !key.endsWith(".mode")) {
                parsedTemplates.put(key, Text.parse(value, values.get(key.startsWith("format.") ? "format.mode" : "message.mode")));
                Set<String> tokens = new LinkedHashSet<>();
                var matcher = java.util.regex.Pattern.compile("%[^%\\s]+%").matcher(value);
                while (matcher.find()) tokens.add(matcher.group());
                if (value.contains("{prefix}") || value.contains("<prefix>")) tokens.add(values.get("placeholders.prefix"));
                tokens.remove("");
                if (tokens.size() > 32) throw new IllegalArgumentException("At most 32 PAPI expressions per template: " + key);
                parsedPlaceholders.put(key, Set.copyOf(tokens));
            }
        });
        lists = Map.copyOf(parsedLists); templates = Map.copyOf(parsedTemplates); placeholders = Map.copyOf(parsedPlaceholders);
    }
    Component render(String key, Map<String, String> values) { return Text.render(templates.get(key), values); }
    Component template(String key) { return templates.get(key); }
    Set<String> placeholders(String key) { return placeholders.getOrDefault(key, Set.of()); }
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
        Properties languageDefaults = LanguageSettings.defaults();
        Properties language = LanguageSettings.load(directory);
        Map<String, String> values = new HashMap<>();
        language.forEach((k, v) -> { if (!k.toString().startsWith("teleport.")) values.put(k.toString(), v.toString()); });
        defaults.forEach((k, v) -> values.put(k.toString(), v.toString()));
        configured.forEach((k, v) -> {
            String key = k.toString();
            if (!defaults.containsKey(key) && !(languageDefaults.containsKey(key) && !key.startsWith("teleport.")) && !key.startsWith("routing.group.") && !key.startsWith("display.server."))
                throw new IllegalArgumentException("Unknown setting: " + key);
            if (!languageDefaults.containsKey(key)) values.put(key, v.toString().trim());
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
        for (String key : List.of("announcements.join", "announcements.leave", "announcements.debug", "chat.capture", "private.enabled", "private.allow-self", "private.respect-groups", "placeholders.enabled", "placeholders.inherit-name-color", "suggestions.chat", "suggestions.legacy-tab-list", "staff.toggle"))
            if (!Set.of("true", "false").contains(get(key))) throw new IllegalArgumentException("Expected true/false: " + key);
        for (String key : List.of("chat.cooldown-ms", "staff.cooldown-ms", "private.cooldown-ms"))
            if (number(key) < 0 || number(key) > 60000) throw new IllegalArgumentException("Cooldown must be 0–60000: " + key);
        if (number("chat.max-length") < 1 || number("chat.max-length") > 4096) throw new IllegalArgumentException("chat.max-length must be 1–4096");
        if (number("suggestions.limit") < 1 || number("suggestions.limit") > 500) throw new IllegalArgumentException("suggestions.limit must be 1–500");
        if (!Set.of("global", "local").contains(get("chat.default-channel"))) throw new IllegalArgumentException("Default channel must be global or local");
        for (String key : List.of("permission.staff-send", "permission.staff-receive", "permission.admin", "permission.find", "permission.hidden", "permission.see-hidden", "permission.bypass-cooldown"))
            if (get(key).isBlank()) throw new IllegalArgumentException("Privileged permission must not be empty: " + key);
        for (String key : List.of("format.mode", "message.mode", "placeholders.format"))
            if (!Set.of("legacy", "minimessage", "plain").contains(get(key))) throw new IllegalArgumentException("Expected legacy/minimessage/plain: " + key);
        for (String key : List.of("suggestions.chat-limit", "suggestions.legacy-tab-limit"))
            if (number(key) < 1 || number(key) > 100000) throw new IllegalArgumentException("Limit must be 1–100000: " + key);
        if (number("suggestions.refresh-ms") < 250 || number("suggestions.refresh-ms") > 60000) throw new IllegalArgumentException("Refresh must be 250–60000 ms");
        if (number("placeholders.timeout-ms") < 50 || number("placeholders.timeout-ms") > 10000) throw new IllegalArgumentException("PAPI timeout must be 50–10000 ms");
        if (number("placeholders.cache-ms") < 0 || number("placeholders.cache-ms") > 3600000) throw new IllegalArgumentException("PAPI cache must be 0–3600000 ms");
        Text.expansion(get("placeholders.fallback"), get("placeholders.format"));
        if (!Set.of("network", "group").contains(get("announcements.scope"))) throw new IllegalArgumentException("announcements.scope must be network or group");
        Set<String> aliases = new HashSet<>();
        for (String kind : List.of("global", "staff", "message", "reply", "ignore", "toggle", "channel", "whereami", "find", "admin")) {
            if (list("command." + kind).isEmpty()) throw new IllegalArgumentException("Empty command: " + kind);
            for (String alias : get("command." + kind).split(",")) {
                alias = alias.trim().toLowerCase(Locale.ROOT);
                if (!alias.matches("[a-z][a-z0-9_-]*") || !aliases.add(alias)) throw new IllegalArgumentException("Invalid/duplicate command alias: " + alias);
            }
        }
    }
}
