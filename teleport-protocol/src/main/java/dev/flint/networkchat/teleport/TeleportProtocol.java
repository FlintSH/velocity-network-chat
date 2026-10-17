package dev.flint.networkchat.teleport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Fixed-size, authenticated protocol. Replay and operation state belong to each endpoint. */
public final class TeleportProtocol {
    public static final String CHANNEL = "networkchat:teleport";
    public static final long MAX_PACKET_AGE_MILLIS = 10_000;
    public static final long MAX_OPERATION_MILLIS = 30_000;
    private static final int MAGIC = 0x4E435450;
    private static final int VERSION = 1;
    private static final int BODY_BYTES = 4 + 1 + 1 + 16 * 4 + 8 * 2;
    private static final int MAC_BYTES = 32;
    public static final int PACKET_BYTES = BODY_BYTES + MAC_BYTES;

    private TeleportProtocol() { }

    public enum Type { PROBE, READY, EXECUTE, SUCCESS, WAIT, FAILED, CANCEL }

    public record Packet(Type type, UUID operation, UUID nonce, UUID mover, UUID target,
                         long issuedAt, long expiresAt) {
        public Packet {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(mover, "mover");
            Objects.requireNonNull(target, "target");
            if (issuedAt < 0 || expiresAt <= issuedAt || expiresAt - issuedAt > MAX_OPERATION_MILLIS)
                throw new IllegalArgumentException("Invalid teleport deadline");
        }
    }

    public static void validateSecret(String secret) {
        if (secret == null || secret.length() < 32 || secret.length() > 4096 || secret.isBlank())
            throw new IllegalArgumentException("Teleport shared secret must contain 32 to 4096 characters");
    }

    public static byte[] encode(Packet packet, String secret) {
        validateSecret(secret);
        Objects.requireNonNull(packet, "packet");
        ByteBuffer buffer = ByteBuffer.allocate(PACKET_BYTES);
        buffer.putInt(MAGIC).put((byte) VERSION).put((byte) packet.type().ordinal());
        putUuid(buffer, packet.operation());
        putUuid(buffer, packet.nonce());
        putUuid(buffer, packet.mover());
        putUuid(buffer, packet.target());
        buffer.putLong(packet.issuedAt()).putLong(packet.expiresAt());
        buffer.put(sign(buffer.array(), secret));
        return buffer.array();
    }

    public static Packet decode(byte[] bytes, String secret, long now) {
        validateSecret(secret);
        if (bytes == null || bytes.length != PACKET_BYTES)
            throw new IllegalArgumentException("Invalid teleport packet length");
        if (!MessageDigest.isEqual(sign(bytes, secret), Arrays.copyOfRange(bytes, BODY_BYTES, PACKET_BYTES)))
            throw new IllegalArgumentException("Invalid teleport authentication");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC || Byte.toUnsignedInt(buffer.get()) != VERSION)
            throw new IllegalArgumentException("Unsupported teleport protocol");
        int type = Byte.toUnsignedInt(buffer.get());
        if (type >= Type.values().length) throw new IllegalArgumentException("Unknown teleport type");
        Packet packet = new Packet(Type.values()[type], getUuid(buffer), getUuid(buffer),
                getUuid(buffer), getUuid(buffer), buffer.getLong(), buffer.getLong());
        // Permit small clock skew in either direction, while the absolute expiry is strict.
        if (now < 0 || packet.issuedAt() > now + MAX_PACKET_AGE_MILLIS
                || packet.issuedAt() < now - MAX_PACKET_AGE_MILLIS || now >= packet.expiresAt())
            throw new IllegalArgumentException("Expired teleport packet");
        return packet;
    }

    private static byte[] sign(byte[] bytes, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(bytes, 0, BODY_BYTES);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static void putUuid(ByteBuffer buffer, UUID id) {
        buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
    }

    private static UUID getUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
