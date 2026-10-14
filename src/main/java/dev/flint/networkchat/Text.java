package dev.flint.networkchat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import java.util.Map;
import java.util.regex.Pattern;

/** Parse administrator-owned formatting first; substitute untrusted input as literal components. */
final class Text {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern TOKEN = Pattern.compile("\\{([a-z-]+)}");
    static Component render(String template, Map<String, String> values) {
        return render(parse(template), values);
    }
    static Component parse(String template) { return LEGACY.deserialize(template); }
    static Component render(Component template, Map<String, String> values) {
        return template.replaceText(builder -> builder.match(TOKEN).replacement((match, ignored) ->
            Component.text(values.getOrDefault(match.group(1), match.group()))));
    }
    static boolean valid(String message, int limit) {
        return !message.isBlank() && message.codePointCount(0, message.length()) <= limit
            && message.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029);
    }
}
