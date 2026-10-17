package dev.flint.networkchat.bridge;

import dev.flint.networkchat.teleport.TeleportProtocol;
import dev.flint.networkchat.teleport.TeleportProtocol.Packet;
import dev.flint.networkchat.teleport.TeleportProtocol.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** All state transitions and platform calls run on the Paper main thread. */
final class TeleportService {
    enum Readiness { READY, LOADING, FAILED }

    record Session(UUID id, Object identity, boolean online, boolean alive, String world) { }

    record Destination(UUID worldId, String world, double x, double y, double z, float yaw, float pitch) {
        int chunkX() { return ((int) Math.floor(x)) >> 4; }
        int chunkZ() { return ((int) Math.floor(z)) >> 4; }
        boolean sameChunk(Destination other) {
            return worldId.equals(other.worldId) && chunkX() == other.chunkX() && chunkZ() == other.chunkZ();
        }
    }

    interface Platform {
        long now();
        boolean active();
        Session player(UUID id);
        Readiness readiness(UUID id);
        boolean allowed(String world);
        Destination location(Session player);
        CompletionStage<Void> load(Destination destination);
        void mainThread(Runnable action);
        boolean teleport(Session player, Destination destination);
    }

    private static final long RETAIN_MILLIS = 60_000;
    private final Platform platform;
    private final int capacity;
    private final Map<UUID, Seen> nonces = new LinkedHashMap<>();
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();
    private long nextPrune;
    private long denyNewUntil;

    TeleportService(Platform platform, int capacity) {
        this.platform = platform;
        this.capacity = capacity;
    }

    void clear() {
        nonces.clear();
        operations.clear();
        nextPrune = 0;
        denyNewUntil = 0;
    }

    void receive(Packet request, Consumer<Packet> reply) {
        long now = platform.now();
        if (!platform.active() || now >= request.expiresAt()
                || (request.type() != Type.PROBE && request.type() != Type.EXECUTE && request.type() != Type.CANCEL)) return;
        prune(now);
        Seen previous = nonces.get(request.nonce());
        if (previous != null) {
            // Re-sending the identical packet is safe; changing any field with the same nonce is not.
            if (previous.request.equals(request)) respond(request, previous.result, reply);
            return;
        }
        if (request.type() == Type.CANCEL) {
            cancel(request, reply, now);
            return;
        }
        if (nonces.size() >= capacity) {
            respond(request, Type.FAILED, reply);
            return;
        }
        Seen seen = new Seen(request, now + RETAIN_MILLIS);
        nonces.put(request.nonce(), seen);
        Operation op = operations.get(request.operation());
        if (op == null) {
            if (request.type() != Type.PROBE || operations.size() >= capacity || now < denyNewUntil) {
                finish(request, seen, Type.FAILED, reply);
                return;
            }
            op = new Operation(request.mover(), request.target(), now + RETAIN_MILLIS);
            operations.put(request.operation(), op);
        }
        if (!op.mover.equals(request.mover()) || !op.target.equals(request.target())
                || op.mover.equals(op.target)) {
            finish(request, seen, Type.FAILED, reply);
            return;
        }
        if (op.terminal != null) {
            finish(request, seen, op.terminal, reply);
            return;
        }
        Session target = platform.player(op.target);
        Type validation = validate(target);
        if (validation == Type.READY && op.targetIdentity != null && target.identity() != op.targetIdentity)
            validation = Type.FAILED;
        if (validation != Type.READY) {
            if (validation == Type.FAILED) op.terminal = Type.FAILED;
            finish(request, seen, validation, reply);
            return;
        }
        if (request.type() == Type.PROBE) {
            op.targetIdentity = target.identity();
            finish(request, seen, Type.READY, reply);
            return;
        }
        if (op.targetIdentity == null) {
            finish(request, seen, Type.FAILED, reply);
            return;
        }
        Session mover = platform.player(op.mover);
        validation = validate(mover);
        if (validation == Type.READY && op.moverIdentity != null && mover.identity() != op.moverIdentity)
            validation = Type.FAILED;
        if (validation != Type.READY) {
            if (validation == Type.FAILED) op.terminal = Type.FAILED;
            finish(request, seen, validation, reply);
            return;
        }
        if (op.running != null && now < op.running.request.expiresAt()) {
            finish(request, seen, Type.WAIT, reply);
            return;
        }
        // An expired attempt may be replaced. Its asynchronous callback cannot execute later.
        Destination destination = platform.location(target);
        op.moverIdentity = mover.identity();
        Attempt attempt = new Attempt(request, mover.identity(), target.identity());
        op.running = attempt;
        Operation captured = op;
        finish(request, seen, Type.WAIT, reply);
        try {
            platform.load(destination).whenComplete((unused, error) -> platform.mainThread(() -> {
                if (!platform.active() || operations.get(request.operation()) != captured
                        || captured.running != attempt) return;
                captured.running = null;
                long completedAt = platform.now();
                if (completedAt >= request.expiresAt()
                        || completedAt - request.issuedAt() > TeleportProtocol.MAX_PACKET_AGE_MILLIS) return;
                if (error != null) {
                    captured.terminal = Type.FAILED;
                    finish(request, seen, Type.FAILED, reply);
                    return;
                }
                Session currentMover = platform.player(request.mover());
                Session currentTarget = platform.player(request.target());
                Type result = validate(currentMover);
                Type targetResult = validate(currentTarget);
                if (result == Type.READY) result = targetResult;
                if (currentMover == null || currentTarget == null
                        || currentMover.identity() != attempt.moverIdentity
                        || currentTarget.identity() != attempt.targetIdentity) result = Type.FAILED;
                if (result == Type.READY) {
                    Destination latest = platform.location(currentTarget);
                    if (platform.now() >= request.expiresAt()) return;
                    if (!destination.sameChunk(latest)) {
                        // The target moved away while the chunk loaded. A fresh attempt loads its new chunk.
                        result = Type.WAIT;
                    } else {
                        try {
                            result = platform.teleport(currentMover, latest) ? Type.SUCCESS : Type.FAILED;
                        } catch (RuntimeException ignored) {
                            result = Type.FAILED;
                        }
                    }
                }
                if (result == Type.SUCCESS || result == Type.FAILED) captured.terminal = result;
                finish(request, seen, result, reply);
            }));
        } catch (RuntimeException ignored) {
            op.running = null;
            op.terminal = Type.FAILED;
            finish(request, seen, Type.FAILED, reply);
        }
    }

    private void cancel(Packet request, Consumer<Packet> reply, long now) {
        Operation op = operations.get(request.operation());
        if (op != null && (!op.mover.equals(request.mover()) || !op.target.equals(request.target()))) return;
        Type result = Type.FAILED;
        if (op != null && op.terminal == Type.SUCCESS) result = Type.SUCCESS;
        else {
            if (op == null && operations.size() < capacity) {
                op = new Operation(request.mover(), request.target(), now + RETAIN_MILLIS);
                operations.put(request.operation(), op);
            }
            if (op != null) {
                op.terminal = Type.FAILED;
                op.running = null;
            } else {
                // Preserve fail-closed ordering without evicting replay protection or growing memory.
                // No packet already in flight can be fresh after this gate expires.
                denyNewUntil = Math.max(denyNewUntil, now + TeleportProtocol.MAX_PACKET_AGE_MILLIS + 1);
            }
        }
        // Cancellation must still invalidate an existing operation when the nonce cache is full.
        if (nonces.size() < capacity) {
            Seen seen = new Seen(request, now + RETAIN_MILLIS);
            seen.result = result;
            nonces.put(request.nonce(), seen);
        }
        respond(request, result, reply);
    }

    private Type validate(Session player) {
        if (player == null || !player.online() || !player.alive() || !platform.allowed(player.world()))
            return Type.FAILED;
        return switch (platform.readiness(player.id())) {
            case READY -> Type.READY;
            case LOADING -> Type.WAIT;
            case FAILED -> Type.FAILED;
        };
    }

    private void finish(Packet request, Seen seen, Type result, Consumer<Packet> reply) {
        seen.result = result;
        respond(request, result, reply);
    }

    private void respond(Packet request, Type result, Consumer<Packet> reply) {
        long now = platform.now();
        if (now >= request.expiresAt()) return;
        // A slightly slower backend clock must not make a 30-second probe response
        // violate the protocol's maximum deadline span. Retain the authenticated expiry.
        long issuedAt = Math.max(now, request.issuedAt());
        reply.accept(new Packet(result, request.operation(), request.nonce(), request.mover(), request.target(),
                issuedAt, request.expiresAt()));
    }

    private void prune(long now) {
        if (now < nextPrune) return;
        nextPrune = now + 1000;
        pruneEntries(nonces, now, seen -> seen.until);
        pruneEntries(operations, now, op -> op.until);
    }

    private static <T> void pruneEntries(Map<UUID, T> entries, long now, java.util.function.ToLongFunction<T> expiry) {
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            if (expiry.applyAsLong(iterator.next()) > now) break;
            iterator.remove();
        }
    }

    private static final class Seen {
        final Packet request;
        final long until;
        Type result = Type.WAIT;
        Seen(Packet request, long until) { this.request = request; this.until = until; }
    }

    private static final class Operation {
        final UUID mover, target;
        final long until;
        Object targetIdentity, moverIdentity;
        Type terminal;
        Attempt running;
        Operation(UUID mover, UUID target, long until) { this.mover = mover; this.target = target; this.until = until; }
    }

    private record Attempt(Packet request, Object moverIdentity, Object targetIdentity) { }
}
