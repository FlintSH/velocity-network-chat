package dev.flint.networkchat.formatting;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/** String-only boundary: the caller's native Adventure types never cross into shaded libraries. */
public final class MiniMessageParser {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private MiniMessageParser() { }
    public static String parse(String template, boolean builtins) {
        TagResolver.Builder tags = TagResolver.builder();
        if (builtins) for (String name : new String[]{"player", "target", "server", "message", "prefix", "state", "channel", "limit", "players", "sessions", "signed", "capture", "server-id", "join", "leave", "scope", "presence", "hidden", "announcement-debug", "command", "seconds", "accept", "deny", "cancel"})
            tags.tag(name, Tag.inserting(Component.text("{" + name + "}")));
        return GsonComponentSerializer.gson().serialize(MINI.deserialize(template, tags.build()));
    }
}
