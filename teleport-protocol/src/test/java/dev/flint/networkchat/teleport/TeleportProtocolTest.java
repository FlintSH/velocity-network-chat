package dev.flint.networkchat.teleport;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class TeleportProtocolTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final long NOW = 1_800_000_000_000L;

    TeleportProtocol.Packet packet(long issued, long expires) {
        return new TeleportProtocol.Packet(TeleportProtocol.Type.EXECUTE, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), issued, expires);
    }

    @Test void everyMessageTypeRoundTrips() {
        var original = packet(NOW, NOW + 3000);
        for (var type : TeleportProtocol.Type.values()) {
            var p = new TeleportProtocol.Packet(type, original.operation(), original.nonce(), original.mover(),
                    original.target(), NOW, NOW + 3000);
            byte[] encoded = TeleportProtocol.encode(p, SECRET);
            assertEquals(TeleportProtocol.PACKET_BYTES, encoded.length);
            assertEquals(p, TeleportProtocol.decode(encoded, SECRET, NOW));
        }
    }

    @Test void everyAlteredByteFailsAuthentication() {
        byte[] encoded = TeleportProtocol.encode(packet(NOW, NOW + 3000), SECRET);
        for (int i = 0; i < encoded.length; i++) {
            byte[] modified = encoded.clone();
            modified[i] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> TeleportProtocol.decode(modified, SECRET, NOW));
        }
    }

    @Test void wrongSecretFails() {
        var encoded = TeleportProtocol.encode(packet(NOW, NOW + 3000), SECRET);
        assertThrows(IllegalArgumentException.class, () -> TeleportProtocol.decode(encoded, "f".repeat(64), NOW));
    }

    @Test void lengthBoundsRejectTruncatedTrailingAndOversizedInput() {
        var encoded = TeleportProtocol.encode(packet(NOW, NOW + 3000), SECRET);
        assertThrows(IllegalArgumentException.class, () -> TeleportProtocol.decode(null, SECRET, NOW));
        for (int length : new int[]{0, 1, encoded.length - 1, encoded.length + 1, 32767}) {
            byte[] modified = Arrays.copyOf(encoded, length);
            assertThrows(IllegalArgumentException.class, () -> TeleportProtocol.decode(modified, SECRET, NOW));
        }
    }

    @Test void rejectsStaleFutureAndExpiredRequests() {
        assertThrows(IllegalArgumentException.class, () -> TeleportProtocol.decode(
                TeleportProtocol.encode(packet(NOW - 10001, NOW + 1), SECRET), SECRET, NOW));
        assertThrows(IllegalArgumentException.class, () -> TeleportProtocol.decode(
                TeleportProtocol.encode(packet(NOW + 10001, NOW + 11000), SECRET), SECRET, NOW));
        assertThrows(IllegalArgumentException.class, () -> TeleportProtocol.decode(
                TeleportProtocol.encode(packet(NOW - 1000, NOW), SECRET), SECRET, NOW));
    }

    @Test void malformedAuthenticatedHeaderStillFails() throws Exception {
        var encoded = TeleportProtocol.encode(packet(NOW, NOW + 3000), SECRET);
        for (int offset : new int[]{0, 4, 5}) {
            byte[] modified = encoded.clone();
            modified[offset] = (byte) 0xff;
            resign(modified);
            assertThrows(IllegalArgumentException.class, () -> TeleportProtocol.decode(modified, SECRET, NOW));
        }
    }

    @Test void deadlineAndSecretMustBeBounded() {
        for (String weak : new String[]{null, "", " ".repeat(64), "x".repeat(31), "x".repeat(4097)})
            assertThrows(IllegalArgumentException.class, () -> TeleportProtocol.encode(packet(NOW, NOW + 3000), weak));
        assertThrows(IllegalArgumentException.class, () -> packet(-1, 3000));
        assertThrows(IllegalArgumentException.class, () -> packet(NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> packet(NOW, NOW + 30001));
    }

    private static void resign(byte[] bytes) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signature = mac.doFinal(Arrays.copyOf(bytes, bytes.length - 32));
        System.arraycopy(signature, 0, bytes, bytes.length - 32, signature.length);
    }
}
