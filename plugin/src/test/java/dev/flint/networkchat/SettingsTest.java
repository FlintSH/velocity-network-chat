package dev.flint.networkchat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @TempDir Path directory;
    @Test void createsDefaultsAndMergesNewKeys() throws Exception {
        Settings initial = Settings.load(directory);
        assertTrue(Files.exists(directory.resolve("config.properties")));
        assertEquals(256, initial.number("chat.max-length"));
        Files.writeString(directory.resolve("config.properties"), "config-version=1\nchat.cooldown-ms=12\nrouting.group.Lobby=hub\n");
        Settings merged = Settings.load(directory);
        assertEquals(12, merged.number("chat.cooldown-ms"));
        assertEquals("networkchat.admin", merged.get("permission.admin"));
    }
    @Test void rejectsUnsafeOrMistypedConfiguration() throws Exception {
        for (String input : new String[]{"config-version=2", "chat.cooldown-ms=-1", "chat.max-length=99999", "chat.capture=yes", "permission.admin=", "permission.find=", "command.global=msg", "command.staff=bad alias", "typo.setting=true", "command.global=g,g", "suggestions.limit=0", "command.find=whereami", "command.whereami=", "announcements.debug=yes"}) {
            Files.writeString(directory.resolve("config.properties"), input);
            assertThrows(IllegalArgumentException.class, () -> Settings.load(directory), input);
        }
    }
    @Test void routesAndLabelsAreConfigurable() throws Exception {
        Files.writeString(directory.resolve("config.properties"), "routing.group.lobby=hub\ndisplay.server.lobby=Main Lobby\nchat.signed-backends=Lobby, Survival\nchat.excluded-servers=auth\n");
        Settings config = Settings.load(directory);
        assertEquals("hub", config.group("Lobby")); assertEquals("Main Lobby", config.label("Lobby"));
        assertTrue(config.signedBackend("LOBBY")); assertFalse(config.signedBackend("other"));
        assertTrue(config.excluded("AUTH"));
    }
    @Test void newLocationDefaultsFollowExistingMessageModeAndExposeServerIdTag() throws Exception {
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        for (String mode : java.util.List.of("minimessage", "plain")) {
            Files.writeString(directory.resolve("config.properties"), "message.mode=" + mode + "\n");
            Settings config = Settings.load(directory);
            assertEquals("You are on Survival.", plain.serialize(config.render("message.whereami", java.util.Map.of("server", "Survival"))));
            assertEquals("Bob is on Survival.", plain.serialize(config.render("message.find", java.util.Map.of("target", "Bob", "server", "Survival"))));
        }
        Files.writeString(directory.resolve("config.properties"), "message.mode=minimessage\nmessage.whereami=<gold><server> (<server-id>)</gold>\n");
        assertEquals("Survival (survival-1)", plain.serialize(Settings.load(directory).render("message.whereami", java.util.Map.of("server", "Survival", "server-id", "survival-1"))));
    }
}
