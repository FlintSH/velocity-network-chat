package dev.flint.networkchat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import dev.flint.networkchat.formatting.MiniMessageParser;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import java.util.Map;
import java.util.regex.Pattern;

/** Parse trusted templates/expansions first, then insert player input as literal components. */
final class Text {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final Pattern TOKEN = Pattern.compile("\\{([a-z-]+)}|%([^%\\s]+)%");
    static Component render(String template, Map<String, String> values) { return render(parse(template), values); }
    static Component parse(String template) { return parse(template, "legacy"); }
    static Component parse(String template, String mode) {
        if (mode.equals("plain")) return Component.text(template);
        if (mode.equals("legacy")) return LEGACY.deserialize(template.replace('§', '&'));
        return GsonComponentSerializer.gson().deserialize(MiniMessageParser.parse(template, true));
    }
    static Component expansion(String value, String mode) {
        // Both '&' and section-sign legacy prefixes are common in PAPI expansions.
        return mode.equals("legacy") ? LEGACY.deserialize(value.replace('§', '&')) : mode.equals("plain") ? Component.text(value) : GsonComponentSerializer.gson().deserialize(MiniMessageParser.parse(value, false));
    }
    static Component render(Component template, Map<String, String> values) {
        return renderComponents(template, values.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> Component.text(e.getValue()))));
    }
    static Component renderComponents(Component template, Map<String, Component> values) {
        return template.replaceText(builder -> builder.match(TOKEN).replacement((match, ignored) ->
            values.getOrDefault(match.group(1) != null ? match.group(1) : match.group(), Component.text(match.group()))));
    }
    /** Last rank-word colour; decorative brackets/whitespace never override the rank itself. */
    static TextColor rankColor(Component prefix) {
        record Frame(Component component, TextColor inherited) { }
        java.util.ArrayDeque<Frame> pending = new java.util.ArrayDeque<>();
        pending.push(new Frame(prefix, null));
        TextColor word = null, symbol = null;
        while (!pending.isEmpty()) {
            Frame frame = pending.pop();
            Component component = frame.component();
            TextColor color = component.color() == null ? frame.inherited() : component.color();
            if (component instanceof TextComponent text && color != null) {
                if (text.content().codePoints().anyMatch(Character::isLetterOrDigit)) word = color;
                if (text.content().codePoints().anyMatch(c -> {
                    int type = Character.getType(c);
                    return type == Character.MATH_SYMBOL || type == Character.CURRENCY_SYMBOL || type == Character.MODIFIER_SYMBOL || type == Character.OTHER_SYMBOL;
                })) symbol = color;
            }
            var children = component.children();
            for (int i = children.size() - 1; i >= 0; i--) pending.push(new Frame(children.get(i), color));
        }
        return word == null ? symbol : word;
    }
    static boolean valid(String message, int limit) {
        return !message.isBlank() && message.codePointCount(0, message.length()) <= limit
            && message.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029);
    }
}
