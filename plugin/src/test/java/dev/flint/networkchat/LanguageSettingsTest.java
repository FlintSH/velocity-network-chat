package dev.flint.networkchat;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LanguageSettingsTest {
    @TempDir Path directory;
    private String text(Settings settings, String key) {
        return PlainTextComponentSerializer.plainText().serialize(settings.render(key, Map.of()));
    }

    @Test void newInstallSeparatesLanguageFromOperationalDefaults() throws Exception {
        Settings settings = Settings.load(directory);
        TeleportSettings.load(directory);
        assertTrue(Files.exists(directory.resolve("language.properties")));
        for (String file : List.of("config.properties", "teleport.properties")) {
            Properties properties = LanguageSettings.read(directory.resolve(file));
            assertFalse(properties.stringPropertyNames().stream().anyMatch(k -> k.startsWith("message.") && !k.equals("message.mode")));
            assertFalse(properties.stringPropertyNames().stream().anyMatch(k -> k.startsWith("format.") && !k.equals("format.mode")));
        }
        assertEquals("You do not have permission.", text(settings, "message.no-permission"));
        assertTrue(LanguageSettings.read(directory.resolve("language.properties")).containsKey("teleport.message.sent"));
    }

    @Test void importsLegacyWordingWithoutRewritingConfigurationOrCopyingSecrets() throws Exception {
        String chat = "chat.max-length=123\nmessage.no-permission=&cAccès refusé ✓\nformat.global={player}: {message}\\\\fin\n";
        String teleport = "shared-secret=a-private-test-secret-at-least-32-characters\nmessage.no-permission=&cTéléportation refusée\n";
        Files.writeString(directory.resolve("config.properties"), chat);
        Files.writeString(directory.resolve("teleport.properties"), teleport);
        Settings settings = Settings.load(directory);
        TeleportSettings teleports = TeleportSettings.load(directory);
        Properties migrated = LanguageSettings.read(directory.resolve("language.properties"));
        assertEquals("&cAccès refusé ✓", migrated.getProperty("message.no-permission"));
        assertEquals("{player}: {message}\\fin", migrated.getProperty("format.global"));
        assertEquals("&cTéléportation refusée", migrated.getProperty("teleport.message.no-permission"));
        assertEquals("Accès refusé ✓", text(settings, "message.no-permission"));
        assertEquals("Téléportation refusée", PlainTextComponentSerializer.plainText().serialize(teleports.message("no-permission", Map.of())));
        assertFalse(Files.readString(directory.resolve("language.properties")).contains("private-test-secret"));
        assertEquals(chat, Files.readString(directory.resolve("config.properties")));
        assertEquals(teleport, Files.readString(directory.resolve("teleport.properties")));
        Files.writeString(directory.resolve("config.properties"), "chat.max-length=123\n");
        Files.writeString(directory.resolve("teleport.properties"), "");
        assertEquals("Accès refusé ✓", text(Settings.load(directory), "message.no-permission"));
        assertEquals("Téléportation refusée", PlainTextComponentSerializer.plainText().serialize(TeleportSettings.load(directory).message("no-permission", Map.of())));
    }

    @Test void languageWinsAndPartialFilesFallBackWithoutBeingRewritten() throws Exception {
        Files.writeString(directory.resolve("config.properties"), "message.no-permission=Old denial\nmessage.cooldown=Old cooldown\n");
        Files.writeString(directory.resolve("teleport.properties"), "message.no-permission=Old teleport denial\n");
        String language = "message.no-permission=New denial\nteleport.message.no-permission=New teleport denial\n";
        Files.writeString(directory.resolve("language.properties"), language);
        Settings settings = Settings.load(directory);
        assertEquals("New denial", text(settings, "message.no-permission"));
        assertEquals("Old cooldown", text(settings, "message.cooldown"));
        assertEquals("That player is unavailable.", text(settings, "message.unavailable"));
        assertEquals("New teleport denial", PlainTextComponentSerializer.plainText().serialize(TeleportSettings.load(directory).message("no-permission", Map.of())));
        assertEquals(language, Files.readString(directory.resolve("language.properties")));
    }

    @Test void rejectsUnknownLanguageKeysAndOperationalSettingsInLanguage() throws Exception {
        for (String key : List.of("message.typo", "permission.admin", "teleport.shared-secret", "format.mode")) {
            Files.writeString(directory.resolve("language.properties"), key + "=value\n");
            assertThrows(IllegalArgumentException.class, () -> Settings.load(directory));
            assertThrows(IllegalArgumentException.class, () -> TeleportSettings.load(directory));
        }
        Files.writeString(directory.resolve("language.properties"), "");
        Files.writeString(directory.resolve("config.properties"), "teleport.message.sent=wrong file\n");
        assertThrows(IllegalArgumentException.class, () -> Settings.load(directory));
    }

    @Test void migrationRetainsIndependentModesEmptyTextAndPropertiesEscapes() throws Exception {
        Files.writeString(directory.resolve("config.properties"), "message.mode=minimessage\nformat.mode=plain\n"
            + "message.no-permission=<gold>Chat: é</gold>\nmessage.cooldown=\n"
            + "message.self=First\\\n  second\\: \\= end\n");
        Files.writeString(directory.resolve("teleport.properties"), "message-mode=plain\nmessage.no-permission=Teleport &c literal\n");
        Settings chat = Settings.load(directory);
        assertEquals("Chat: é", text(chat, "message.no-permission"));
        assertEquals("", text(chat, "message.cooldown"));
        assertEquals("Firstsecond: = end", text(chat, "message.self"));
        assertEquals("[+] {player} joined the network.", chat.get("format.join"));
        assertEquals("Teleport &c literal", PlainTextComponentSerializer.plainText().serialize(
            TeleportSettings.load(directory).message("no-permission", Map.of())));
        assertEquals("Firstsecond: = end", text(Settings.load(directory), "message.self"));
    }
}
