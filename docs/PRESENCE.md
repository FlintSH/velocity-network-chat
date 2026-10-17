# Proxy announcements and player locations

Network Chat announces one network join after the player's first eligible backend
is ready, then one leave when that proxy session disconnects. Backend switches do
not create another join. A server listed in `chat.excluded-servers`, such as an
authentication lobby, delays the join until the player reaches an eligible server.
Disconnecting entirely within excluded servers produces no announcement.

The join listener uses Velocity's `ServerPostConnectEvent`, which guarantees the
current backend is available. The earlier `ServerConnectedEvent` fires before
Velocity updates that connection and sends the client its join-game transition;
using it caused the joining player to miss their own message. Both events exist
in the supported Velocity 3.1.1 API. See the [official event contract](https://jd.papermc.io/velocity/3.4.0/com/velocitypowered/api/event/player/ServerPostConnectEvent.html)
and [Velocity's transition implementation](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/connection/backend/TransitionSessionHandler.java).

That lifecycle defect does not establish why every observer might miss both joins
and leaves on a particular installation. The effective configuration and
permissions also determine the audience.

## Configuration

These defaults also apply when the keys are absent from an older
`plugins/networkchat/config.properties` file. Existing files are preserved.
Add or update the relevant lines, then run `/networkchat reload`:

```properties
announcements.join=true
announcements.leave=true
announcements.scope=network
announcements.debug=false
permission.announcements=
format.join=&8[&a+&8] &f{player} &7joined the network.
format.leave=&8[&c-&8] &f{player} &7left the network.
```

`scope=group` confines announcements to the subject's routing group. Recipients
must be active on an eligible backend and have `permission.announcements` if it
is configured. The format uses `format.mode`; `{player}` is the username and
`{server}` is the configured display label. Leave messages use the last recorded
backend after the live connection disappears. Backend PAPI lookups cannot run
after disconnect, so leave formats use the configured placeholder fallback.

`permission.hidden` defaults to `networkchat.hidden`. A player with that
permission is hidden from ordinary viewers; `networkchat.see.hidden` allows staff
to see them. Check inherited wildcard grants: granting `networkchat.*` can also
grant `networkchat.hidden`. Explicitly deny the hidden node for staff who should
remain visible. Do not disable privacy checks to troubleshoot announcements.

Announcements do not depend on SignedVelocity, `chat.capture`, or the global chat
receive permission. The backend's own join/quit messages are separate; configure
those on Paper if the proxy announcement would duplicate them.

## Diagnosis

Run `/networkchat status` with `networkchat.admin`. The bundled status message
shows the join/leave switches, scope, tracked sessions and hidden-player count.
If you customized `message.status`, add the corresponding placeholders from the
bundled configuration or inspect the startup log.

For a short controlled test, set `announcements.debug=true`, reload, and connect a
test player while another eligible player is already online. The proxy log records
the ready/disconnected lifecycle and announcement recipient counts, with separate
counts for unavailable/excluded recipients, missing receive permission, hidden
audience and another routing group. A failed backend login has no ready event and
must not produce a leave. Restore `announcements.debug=false` after the test.

The debug log includes player names and backend names, but never message bodies.

## Location commands

| Command | Default permission | Behavior |
| --- | --- | --- |
| `/whereami` | Everyone | Reports the caller's current backend. Players only. |
| `/find <player>` | `networkchat.find` | Reports an online player's backend. Available to authorized players and console. |

Aliases use `command.whereami` and `command.find` and take effect after a proxy
restart. `permission.whereami` can restrict the public command;
`permission.find` must remain nonempty. Both commands work independently of chat
routing/exclusions. `/find` and its completions honor hidden players and do not
reveal their existence to viewers without `permission.see-hidden`. The permission
and visibility are checked again after any asynchronous placeholder lookup.

`message.whereami` and `message.find` accept `{server}` for the display label and
`{server-id}` for the raw Velocity backend name. `/find` also accepts `{target}`
and `{player}` for the matched player's username. Usage, unavailable-server and
denial messages are configurable in the same file. A nonexistent, hidden,
disconnected or not-yet-connected lookup target produces the same unavailable
response.
