package dev.flint.networkchat;

import net.kyori.adventure.text.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TextTest {
    @Test void userInputCannotInjectFormattingOrRecursivePlaceholders() {
        Component result = Text.render("&a{player}: &f{message}", Map.of("player", "Alice", "message", "&c<click:run_command:/op me>{player}"));
        assertEquals("Alice: &c<click:run_command:/op me>{player}", PlainTextComponentSerializer.plainText().serialize(result));
        assertNull(result.clickEvent());
    }
    @Test void validatesUnicodeWithoutSplittingSurrogatePairs() {
        assertTrue(Text.valid("😀", 1)); assertFalse(Text.valid("😀😀", 1));
        assertFalse(Text.valid("  ", 256)); assertFalse(Text.valid("a\nb", 256));
        assertFalse(Text.valid("a\u202eb", 256)); assertFalse(Text.valid("a\u2028b", 256));
    }
}
