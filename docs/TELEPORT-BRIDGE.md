# Paper teleport bridge

The proxy owns requests, acceptance, permissions and server transfers. Install
`networkchat-paper-bridge-<version>.jar` on each participating Paper/Purpur 26.1.2
server to move players to the target's position. The bridge requires Java 25;
the proxy plugin and wire protocol retain Java 17 compatibility. Folia and other
backend Minecraft versions are not supported by this bridge build.

1. Generate one private secret with `openssl rand -hex 32`.
2. Set `shared-secret` in Velocity's `plugins/networkchat/teleport.properties` and in every
   `plugins/NetworkChatBridge/config.yml` to that value. Enable teleports on
   Velocity. An empty bridge secret disables the bridge.
3. Restart the affected proxy and backend servers. Backend world restrictions
   live in `allowed-worlds` and `blocked-worlds`; blocked entries take precedence.
4. Grant the proxy command permissions described in the main README. The bridge
   does not grant any backend command permissions or register `/tp` itself.

If Network Inventory Sync is installed, update it alongside the bridge. The
bridge calls its public `isReady(UUID)` API on the server main thread and waits
until the player's inventory is ready. A disabled or incompatible inventory
plugin fails closed. Set `require-inventory-sync: true` to also refuse teleports
if the inventory plugin is missing. A player whose inventory never becomes
ready reaches the proxy's configured transfer timeout.

Packets use a fixed-size binary format signed with HMAC-SHA256 on the
`networkchat:teleport` channel. Every request contains an operation ID, nonce,
mover and target UUIDs, issue time and absolute expiry. Clocks on the proxy and
backend machines should be synchronized. Invalid signatures, malformed packets,
old timestamps and expired attempts are discarded. Nonces and completed
operations remain in bounded caches for 60 seconds; full caches reject new
requests rather than evict replay protection early. Increase `replay-cache-size`
for very busy servers.

A successful probe binds the operation to the target's current player session.
Execution checks both players, world restrictions and inventory readiness. The
destination chunk loads asynchronously. On the main thread, execution checks
expiry, player sessions, liveness and inventory readiness again, then teleports
to the target's current position using Bukkit's cancellable `PlayerTeleportEvent`
path. If the target changed chunks during loading, a fresh attempt loads the new
chunk. An expired callback cannot teleport the player. Repeated requests for an
in-flight or successful operation do not teleport twice.

The proxy sends a signed cancellation when an operation becomes invalid or the
plugin shuts down. Receiving it prevents pending chunk callbacks from teleporting
and records a cancellation even if it arrived before the original request. This
is best effort: a delivered teleport cannot be reversed by a later cancellation,
and a disconnected backend may not receive the cancellation. Short signed attempt
deadlines still bound execution when messages are lost.

Teleporting is limited to one Velocity proxy. There is no cross-proxy player
directory or shared request store. After a successful backend transfer, a later
teleport rejection leaves the player on that backend; there is no automatic
rollback that could race inventory synchronization. Protection plugins may cancel
the teleport. Keep backend ports private and configure Velocity modern forwarding;
the signed bridge supplements those network controls.

Build with JDK 25 using `mvn -B -Ppaper verify`. Protocol tests cover forged,
malformed and expired packets. Bridge tests exercise readiness, cancellation,
concurrent retries, duplicate execution, session changes, expiry and bounded
caches using controlled platform doubles. The real-Paper probe is described in
`scripts/bridge-smoke.py`; it uses a temporary local server and controlled player
doubles, not a production network or real player clients.
