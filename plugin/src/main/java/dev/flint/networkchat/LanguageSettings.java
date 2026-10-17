package dev.flint.networkchat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Shared language schema and lossless import of the old configuration templates. */
final class LanguageSettings {
    static Properties defaults() throws IOException {
        Properties defaults = new Properties();
        try (var input = LanguageSettings.class.getResourceAsStream("/language.properties")) {
            if (input == null) throw new IOException("Missing bundled language file");
            defaults.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return defaults;
    }

    static Properties read(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(file)) try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        return properties;
    }

    static Properties load(Path directory) throws IOException {
        Properties values = defaults();
        Properties configured = new Properties();
        Properties chat = read(directory.resolve("config.properties"));
        Properties teleports = read(directory.resolve("teleport.properties"));
        // Import only language keys, never secrets, permissions or routing settings.
        for (String key : values.stringPropertyNames()) {
            Properties source = key.startsWith("teleport.") ? teleports : chat;
            String oldKey = key.startsWith("teleport.") ? key.substring(9) : key;
            if (source.containsKey(oldKey)) configured.setProperty(key, source.getProperty(oldKey).trim());
        }
        Path file = directory.resolve("language.properties");
        boolean create = Files.notExists(file);
        Properties language = read(file);
        for (String key : language.stringPropertyNames()) {
            if (!values.containsKey(key)) throw new IllegalArgumentException("Unknown language setting: " + key);
            configured.setProperty(key, language.getProperty(key).trim());
        }
        values.putAll(configured);
        // Preserve the mode-aware defaults supplied to older installations.
        String formatMode = chat.getProperty("format.mode", "legacy").trim();
        if (!formatMode.equals("legacy")) {
            boolean mini = formatMode.equals("minimessage");
            if (!configured.containsKey("format.join")) values.setProperty("format.join", mini ? "<dark_gray>[<green>+</green>]</dark_gray> <white>{player}</white><gray> joined the network.</gray>" : "[+] {player} joined the network.");
            if (!configured.containsKey("format.leave")) values.setProperty("format.leave", mini ? "<dark_gray>[<red>-</red>]</dark_gray> <white>{player}</white><gray> left the network.</gray>" : "[-] {player} left the network.");
        }
        String messageMode = chat.getProperty("message.mode", "legacy").trim();
        if (!messageMode.equals("legacy")) {
            Map<String, String> added = Map.of("whereami", "You are on {server}.", "find", "{target} is on {server}.",
                "usage-whereami", "/whereami", "usage-find", "/find <player>", "server-unavailable", "You are not connected to a server.");
            added.forEach((key, value) -> {
                if (!configured.containsKey("message." + key)) values.setProperty("message." + key,
                    messageMode.equals("minimessage") ? "<yellow>" + value.replace("<player>", "\\<player>") + "</yellow>" : value);
            });
        }
        if (create) {
            Files.createDirectories(directory);
            // Create exclusively: an administrator's existing file is never overwritten.
            try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                values.store(writer, "Network Chat language (UTF-8). Chat: /networkchat reload. Teleport: restart Velocity.\n"
                    + "Modes remain in config.properties and teleport.properties. Imported legacy wording is preserved.");
            }
        }
        return values;
    }
}
