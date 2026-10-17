package dev.flint.networkchat.bridge;

import static org.junit.jupiter.api.Assertions.*;
import dev.flint.networkchat.teleport.TeleportProtocol.Packet;
import dev.flint.networkchat.teleport.TeleportProtocol.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class TeleportServiceTest {
    final Fake platform = new Fake();
    final TeleportService service = new TeleportService(platform, 100);
    final UUID operation = UUID.randomUUID(), mover = UUID.randomUUID(), target = UUID.randomUUID();
    final List<Packet> replies = new ArrayList<>();

    TeleportServiceTest() { platform.add(mover); platform.add(target); }

    Packet request(Type type) { return request(type, operation); }
    Packet request(Type type, UUID op) {
        return new Packet(type, op, UUID.randomUUID(), mover, target, platform.now, platform.now + 3000);
    }
    void receive(Packet packet) { service.receive(packet, replies::add); }
    Type last() { return replies.getLast().type(); }
    void ready() { receive(request(Type.PROBE)); assertEquals(Type.READY, last()); }

    @Test void waitsForInventoryThenTeleportsOnceToCurrentTargetPosition() {
        ready();
        platform.readiness.put(mover, TeleportService.Readiness.LOADING);
        receive(request(Type.EXECUTE));
        assertEquals(Type.WAIT, last());
        assertEquals(0, platform.loads.size());
        platform.readiness.put(mover, TeleportService.Readiness.READY);
        Packet attempt = request(Type.EXECUTE);
        receive(attempt);
        assertEquals(Type.WAIT, last());
        platform.destination = new TeleportService.Destination(platform.world, "world", 3, 65, 5, 90, 10);
        platform.loads.getFirst().complete(null);
        assertEquals(Type.SUCCESS, last());
        assertEquals(platform.destination, platform.teleportedTo);
        assertEquals(attempt.nonce(), replies.getLast().nonce());
        assertEquals(attempt.expiresAt(), replies.getLast().expiresAt());
        receive(request(Type.EXECUTE));
        receive(attempt);
        assertEquals(Type.SUCCESS, last());
        assertEquals(1, platform.teleports);
        assertEquals(1, platform.loads.size());
    }

    @Test void refusesExecuteWithoutSuccessfulProbe() {
        receive(request(Type.EXECUTE));
        assertEquals(Type.FAILED, last());
        assertEquals(0, platform.loads.size());
        platform.readiness.put(target, TeleportService.Readiness.LOADING);
        receive(request(Type.PROBE));
        assertEquals(Type.WAIT, last());
        platform.readiness.put(target, TeleportService.Readiness.READY);
        receive(request(Type.EXECUTE));
        assertEquals(Type.FAILED, last());
    }

    @Test void duplicateAndConcurrentNoncesDoNotStartAnotherTeleport() {
        ready();
        Packet first = request(Type.EXECUTE);
        receive(first);
        receive(first);
        receive(request(Type.EXECUTE));
        assertEquals(1, platform.loads.size());
        platform.loads.getFirst().complete(null);
        receive(request(Type.EXECUTE));
        assertEquals(Type.SUCCESS, last());
        assertEquals(1, platform.teleports);
    }

    @Test void reusedNonceWithDifferentFieldsIsIgnored() {
        Packet probe = request(Type.PROBE);
        receive(probe);
        int count = replies.size();
        receive(new Packet(Type.EXECUTE, probe.operation(), probe.nonce(), mover, target, probe.issuedAt(), probe.expiresAt()));
        assertEquals(count, replies.size());
        assertTrue(platform.loads.isEmpty());
    }

    @Test void cancellationFailsAndCannotBeRetriedToBypassProtection() {
        ready();
        platform.teleportAllowed = false;
        receive(request(Type.EXECUTE));
        platform.loads.getFirst().complete(null);
        assertEquals(Type.FAILED, last());
        platform.teleportAllowed = true;
        receive(request(Type.EXECUTE));
        assertEquals(Type.FAILED, last());
        assertEquals(1, platform.teleports);
    }

    @Test void neverTeleportsAfterAttemptExpiryAndIgnoresExpiredCallbackAfterRetry() {
        ready();
        receive(request(Type.EXECUTE));
        platform.now += 3001;
        receive(request(Type.EXECUTE));
        assertEquals(2, platform.loads.size());
        platform.loads.getFirst().complete(null);
        assertEquals(0, platform.teleports);
        platform.loads.getLast().complete(null);
        assertEquals(1, platform.teleports);
        assertEquals(Type.SUCCESS, last());
    }

    @Test void expiredAttemptWithNoRetryDoesNotTeleportOrSendLateReply() {
        ready();
        receive(request(Type.EXECUTE));
        int count = replies.size();
        platform.now += 3000;
        platform.loads.getFirst().complete(null);
        assertEquals(0, platform.teleports);
        assertEquals(count, replies.size());
    }

    @Test void targetChangingChunkRetriesWithoutTeleportingToOldLocation() {
        ready();
        receive(request(Type.EXECUTE));
        platform.destination = new TeleportService.Destination(platform.world, "world", 100, 65, 100, 0, 0);
        platform.loads.getFirst().complete(null);
        assertEquals(Type.WAIT, last());
        assertEquals(0, platform.teleports);
        receive(request(Type.EXECUTE));
        platform.loads.getLast().complete(null);
        assertEquals(Type.SUCCESS, last());
    }

    @Test void changingSessionBetweenProbeAndExecuteIsRejected() {
        ready();
        platform.add(target);
        receive(request(Type.EXECUTE));
        assertEquals(Type.FAILED, last());
        assertTrue(platform.loads.isEmpty());
    }

    @Test void sessionChangeWhileChunkLoadsPreventsTeleport() {
        ready();
        receive(request(Type.EXECUTE));
        platform.add(mover);
        platform.loads.getFirst().complete(null);
        assertEquals(Type.FAILED, last());
        assertEquals(0, platform.teleports);
    }

    @Test void sessionChangeAfterExpiredAttemptPreventsRetry() {
        ready();
        receive(request(Type.EXECUTE));
        platform.now += 3001;
        platform.add(mover);
        receive(request(Type.EXECUTE));
        assertEquals(Type.FAILED, last());
        platform.loads.getFirst().complete(null);
        assertEquals(0, platform.teleports);
    }

    @Test void livenessAndWorldRestrictionsAreRecheckedAfterLoading() {
        ready();
        receive(request(Type.EXECUTE));
        platform.allowed = false;
        platform.loads.getFirst().complete(null);
        assertEquals(Type.FAILED, last());
        assertEquals(0, platform.teleports);
    }

    @Test void inventoryFailureIsTerminalWhileLoadingIsRetryable() {
        ready();
        receive(request(Type.EXECUTE));
        platform.readiness.put(mover, TeleportService.Readiness.FAILED);
        platform.loads.getFirst().complete(null);
        assertEquals(Type.FAILED, last());
        assertEquals(0, platform.teleports);
    }

    @Test void offlinedAndDeadTargetsFailProbe() {
        platform.players.remove(target);
        receive(request(Type.PROBE));
        assertEquals(Type.FAILED, last());
        platform.players.put(target, new TeleportService.Session(target, new Object(), true, false, "world"));
        receive(request(Type.PROBE, UUID.randomUUID()));
        assertEquals(Type.FAILED, last());
    }

    @Test void reusedOperationCannotChangeTarget() {
        ready();
        receive(new Packet(Type.EXECUTE, operation, UUID.randomUUID(), mover, UUID.randomUUID(), platform.now, platform.now + 3000));
        assertEquals(Type.FAILED, last());
        assertTrue(platform.loads.isEmpty());
    }

    @Test void shutdownInvalidatesPendingChunkCallback() {
        ready();
        receive(request(Type.EXECUTE));
        service.clear();
        platform.loads.getFirst().complete(null);
        assertEquals(0, platform.teleports);
    }

    @Test void signedCancellationInvalidatesPendingChunkLoad() {
        ready();
        receive(request(Type.EXECUTE));
        receive(request(Type.CANCEL));
        assertEquals(Type.FAILED, last());
        platform.loads.getFirst().complete(null);
        assertEquals(0, platform.teleports);
        receive(request(Type.EXECUTE));
        assertEquals(Type.FAILED, last());
    }

    @Test void cancellationBeforeProbeOrExecuteRecordsTombstone() {
        receive(request(Type.CANCEL));
        receive(request(Type.PROBE));
        assertEquals(Type.FAILED, last());
        receive(request(Type.EXECUTE));
        assertEquals(Type.FAILED, last());
        assertTrue(platform.loads.isEmpty());
    }

    @Test void cancellationCannotUndoCompletedOperation() {
        ready();
        receive(request(Type.EXECUTE));
        platform.loads.getFirst().complete(null);
        receive(request(Type.CANCEL));
        assertEquals(Type.SUCCESS, last());
        receive(request(Type.EXECUTE));
        assertEquals(Type.SUCCESS, last());
        assertEquals(1, platform.teleports);
    }

    @Test void cancellationInvalidatesInFlightWorkEvenWhenNonceCacheIsFull() {
        TeleportService small = new TeleportService(platform, 2);
        small.receive(request(Type.PROBE), replies::add);
        small.receive(request(Type.EXECUTE), replies::add);
        small.receive(request(Type.CANCEL), replies::add);
        platform.loads.getFirst().complete(null);
        assertEquals(0, platform.teleports);
    }

    @Test void maximumLengthProbeCanReplyWithSmallClockSkew() {
        Packet probe = new Packet(Type.PROBE, operation, UUID.randomUUID(), mover, target,
                platform.now + 1, platform.now + 30001);
        receive(probe);
        assertEquals(Type.READY, last());
        assertEquals(probe.expiresAt(), replies.getLast().expiresAt());
    }

    @Test void unknownCancellationAtCapacityStillRejectsReorderedProbeAfterOldEntriesExpire() {
        TeleportService small = new TeleportService(platform, 1);
        small.receive(request(Type.PROBE, UUID.randomUUID()), replies::add);
        platform.now += 59000;
        small.receive(request(Type.CANCEL), replies::add);
        platform.now += 1001;
        small.receive(request(Type.PROBE), replies::add);
        assertEquals(Type.FAILED, last());
        small.receive(request(Type.EXECUTE), replies::add);
        assertEquals(Type.FAILED, last());
        assertTrue(platform.loads.isEmpty());
    }

    @Test void boundedReplayCacheRejectsWorkWithoutEvictingLiveEntries() {
        TeleportService small = new TeleportService(platform, 2);
        Packet probe = request(Type.PROBE);
        small.receive(probe, replies::add);
        small.receive(request(Type.PROBE, UUID.randomUUID()), replies::add);
        small.receive(request(Type.PROBE, UUID.randomUUID()), replies::add);
        assertEquals(Type.FAILED, last());
        small.receive(probe, replies::add);
        assertEquals(Type.READY, last());
        platform.now += 60001;
        small.receive(request(Type.PROBE, UUID.randomUUID()), replies::add);
        assertEquals(Type.READY, last());
    }

    static final class Fake implements TeleportService.Platform {
        long now = 1_800_000_000_000L;
        UUID world = UUID.randomUUID();
        Map<UUID, TeleportService.Session> players = new HashMap<>();
        Map<UUID, TeleportService.Readiness> readiness = new HashMap<>();
        List<CompletableFuture<Void>> loads = new ArrayList<>();
        TeleportService.Destination destination = new TeleportService.Destination(world, "world", 1, 65, 1, 0, 0);
        TeleportService.Destination teleportedTo;
        boolean allowed = true, teleportAllowed = true;
        int teleports;
        void add(UUID id) { players.put(id, new TeleportService.Session(id, new Object(), true, true, "world")); }
        public long now() { return now; }
        public boolean active() { return true; }
        public TeleportService.Session player(UUID id) { return players.get(id); }
        public TeleportService.Readiness readiness(UUID id) { return readiness.getOrDefault(id, TeleportService.Readiness.READY); }
        public boolean allowed(String world) { return allowed; }
        public TeleportService.Destination location(TeleportService.Session player) { return destination; }
        public CompletionStage<Void> load(TeleportService.Destination destination) {
            CompletableFuture<Void> future = new CompletableFuture<>(); loads.add(future); return future;
        }
        public void mainThread(Runnable action) { action.run(); }
        public boolean teleport(TeleportService.Session player, TeleportService.Destination destination) {
            teleports++; teleportedTo = destination; return teleportAllowed;
        }
    }
}
