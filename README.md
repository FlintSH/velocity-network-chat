# Velocity Network Chat

[![Test and release](https://github.com/FlintSH/velocity-network-chat/actions/workflows/release.yml/badge.svg)](https://github.com/FlintSH/velocity-network-chat/actions/workflows/release.yml)

Cross-server chat, a separate permission-gated staff channel, and private messaging for a Velocity network. One JAR with an isolated MiniMessage parser, no database, no telemetry, and no message logging.

Designed for many backend servers behind **one Velocity proxy**. Global chat can span the whole proxy or be partitioned into server groups. Independent proxies are not connected: this release has no Redis transport or distributed player directory.

## Install

1. Download the JAR from [Releases](https://github.com/FlintSH/velocity-network-chat/releases) and place it in the **Velocity proxy's** `plugins/` directory. Do not install it on Paper/Bukkit.
2. Restart Velocity. Configuration is generated in `plugins/networkchat/config.properties`.
3. Grant player permissions on the proxy, for example using LuckPerms Velocity:
   ```text
   /lpv group default permission set networkchat.msg true
   /lpv group staff permission set networkchat.staff.send true
   /lpv group staff permission set networkchat.staff.receive true
   /lpv group admin permission set networkchat.admin true
   ```
4. For ordinary cross-server chat and staff toggling on modern clients, follow the signed-chat setup below. One-off `/g`, `/sc <message>`, and `/msg` work without that integration.
5. For prefixes, install PAPIProxyBridge on the proxy and participating backends, plus PlaceholderAPI and your expansion on the backends. See the formatting example below.
6. Edit the configuration and use `/networkchat reload` from the proxy console or as an administrator.

## Compatibility and signed chat

The published JAR targets Java 17 and the Velocity **3.1.1 API**. CI tests APIs 3.1.1, 3.4.0, and 4.2.0, and starts real proxies with the same baseline JAR in MiniMessage mode. A real-proxy smoke check also verifies the optional PAPIProxyBridge 1.8.4 API loads on Velocity 3.4.0; backend placeholder results are tested with controlled asynchronous resolvers and still need the staging checks on your setup. Use the Java version required by your proxy: the plugin's Java 17 bytecode does not lower Velocity's own requirements.

Minecraft client/backend compatibility is determined by your proxy and backend software. This plugin adds no protocol translation. Older clients receive Adventure components through Velocity's native serialization; unsupported colors/features are downgraded by the proxy. No NMS or protocol packet internals are used.

Minecraft **1.19.1+** signed chat cannot safely be cancelled with a proxy-only chat listener. [PaperMC's FAQ](https://docs.papermc.io/velocity/faq/) explains the restriction and recommends [SignedVelocity](https://github.com/4drian3d/SignedVelocity). For ordinary modern chat:

- Install an appropriate, supported SignedVelocity release on the proxy **and each participating backend**. Check its requirements against your server version; its current backend build is not compatible with every historical Minecraft version.
- List the backend names you have verified in `chat.signed-backends=lobby,survival,skyblock`. Names match Velocity's registered server names. Use `*` only after verifying all backends.
- Keep `chat.capture=true`. Network Chat denies the event before SignedVelocity's final listener coordinates cancellation with the backend.

Without both proxy detection and backend acknowledgement, modern ordinary chat remains with the backend; it is not duplicated or cancelled. `/g <message>` gives cross-server chat regardless. This plugin cannot remotely verify installation of the companion on a backend: listing a server is an operator acknowledgement. Incorrect acknowledgement can cause duplicate/backend chat or disconnects.

Legacy ordinary chat is intercepted directly. Excluded servers always retain their own ordinary chat. Earlier chat-event cancellation is respected; earlier message modifications are used. Install only one plugin that owns normal chat formatting/routing, and verify interaction with moderation plugins before rollout.

## Commands

| Default command | Behavior |
| --- | --- |
| `/g <message>` (`/global`) | Cross-server message to the sender's routing group |
| `/sc` (`/staffchat`), `/sc on`, `/sc off` | Toggle ordinary chat into staff mode; requires safe chat capture |
| `/sc <message>` | One-off staff-only message across all connected backends |
| `/msg <player> <message>` (`/tell`, `/whisper`, `/w`) | Private message across backend servers |
| `/r <message>` (`/reply`) | Reply to the last successful private conversation |
| `/ignore <player>` | Toggle ignoring public and private messages from an online player |
| `/msgtoggle` | Toggle receipt of private messages |
| `/chat global` or `/chat local` | Select routing for ordinary chat where capture is safe |
| `/networkchat reload` (`/nchat`) | Validate and atomically reload configuration |
| `/networkchat status` | Show connected players, session count, and integration state |

Staff mode routes ordinary chat exclusively to staff until you use `/sc off`, toggle `/sc` again, or select `/chat global|local`. It is session-only and resets on disconnect. Enabling it needs both staff send and receive permission and safe chat capture on the current server. While enabled, server switches to excluded or unsafe backends are blocked; turn the mode off before switching. If a reload unexpectedly removes capture support, the next message is blocked and the sender is disconnected to protect staff privacy. Permission revocation never changes a staff message into a public message. `staff.toggle=false` prevents enabling the mode; turning an existing mode off remains available. One-off `/sc <message>` remains usable without capture support. Staff recipients are selected using `staff-receive` for each delivery; removing permission takes effect immediately. Staff messages are not suppressed by player ignore preferences. The sender needs both staff send and receive permission.

Private/staff command aliases are claimed by the proxy even for players without permission, so denied messages are not forwarded to backend commands. Alias collisions with other proxy plugins abort initialization without intercepting chat. A startup failure is prominently logged; backend ordinary chat continues.

## Configuration

Every format, response, command alias, and permission is configurable in the [annotated default configuration](plugin/src/main/resources/config.properties). It is UTF-8 Java Properties: use commas for lists, `true`/`false` for booleans, and escape literal backslashes as `\\`. Unknown keys, unsafe privileged permissions, invalid values, duplicate aliases, and unsupported config versions are rejected. Missing keys inherit bundled defaults, so existing configuration remains usable when defaults are extended. The plugin does not rewrite your file on reload.

Configuration is an immutable snapshot. A failed reload retains the previous settings. Alias changes are validated but require a restart to change registration; all other settings apply to subsequent operations.

### Permissions

| Setting | Default permission | Purpose |
| --- | --- | --- |
| `permission.global-send` | Empty (everyone) | Send global messages |
| `permission.global-receive` | Empty (everyone) | Receive global/local public messages |
| `permission.local-send` | Empty (everyone) | Send local messages |
| `permission.staff-send` | `networkchat.staff.send` | Send staff messages |
| `permission.staff-receive` | `networkchat.staff.receive` | Receive staff messages |
| `permission.private` | `networkchat.msg` | Use messaging, reply, ignore, and receive-toggle commands |
| `permission.admin` | `networkchat.admin` | Reload and inspect status |
| `permission.bypass-cooldown` | `networkchat.bypass.cooldown` | Bypass send cooldowns |
| `permission.hidden` | `networkchat.hidden` | Hide a player from private-message lookup and player suggestions |
| `permission.see-hidden` | `networkchat.see.hidden` | See hidden players in lookup/suggestions |

Privileged permissions cannot be empty. `hidden` is a permission-based integration point; this plugin does not automatically discover another plugin's vanish state. If a hidden player speaks publicly, their public message is delivered normally. All permissions are evaluated on Velocity, so backend-only permission assignments are insufficient.

### Routing example

```properties
routing.default-group=network
routing.group.lobby=hub
routing.group.lobby2=hub
routing.group.survival=survival
routing.group.creative=survival
display.server.lobby=Main Lobby
chat.excluded-servers=auth,limbo
private.respect-groups=false
```

`lobby` and `lobby2` share global chat; `survival` and `creative` share a different channel. Unlisted backends share `network`. Staff chat spans groups and reaches permitted staff even on excluded servers, though sending network messages from excluded servers is disabled. Private messages cross groups by default; set `private.respect-groups=true` to confine them. Private messages involving excluded servers are blocked.

### Formatting and limits

Formats support **MiniMessage**, legacy `&`/section-sign codes, or plain text. Existing configuration keeps `format.mode=legacy`; `message.mode` independently controls command/error responses so enabling MiniMessage for chat does not break existing response templates. MiniMessage runs in a shaded parser module with a string/JSON boundary, keeping its Adventure libraries isolated from the old or current proxy's libraries.

Example for MiniMessage chat with a legacy-colored LuckPerms prefix:

```properties
format.mode=minimessage
placeholders.enabled=true
placeholders.prefix=%luckperms_prefix%
placeholders.format=legacy
format.global=<dark_gray>[<aqua>{server}</aqua>]</dark_gray> {prefix}<white>{player}</white><gray>: {message}</gray>
format.local=<dark_gray>[Local]</dark_gray> {prefix}<white>{player}</white><gray>: {message}</gray>
format.staff=<dark_gray>[<red>Staff</red>] [{server}]</dark_gray> {prefix}<white>{player}</white><yellow>: {message}</yellow>
format.private-sent=<dark_gray>[You → {target}]</dark_gray> <white>{message}</white>
format.private-received=<dark_gray>[{player} → You]</dark_gray> <white>{message}</white>
```

Install [PAPIProxyBridge](https://modrinth.com/plugin/papiproxybridge) on the proxy **and** backends, and PlaceholderAPI with the relevant expansion on each backend. `%luckperms_prefix%` needs the LuckPerms expansion and backend LuckPerms data; `%vault_prefix%` can be used with the matching backend Vault setup. Check the bridge's requirements for each server version. The bridge is optional; chat still works when it is absent. The plugin resolves placeholders using the **sender's** UUID/current backend, including both private-message formats. You can put raw `%placeholder_name%` expressions directly in formats and player-facing response templates, or use `{prefix}` for the configured expression.

`placeholders.format=legacy|minimessage|plain` controls styling returned by expansions independently of template styling. Use `plain` if you want expansion text to remain literal. Backend expansions are trusted formatting sources, while player input is always literal: `<click:...>`, legacy color codes, `%placeholders%`, and nested built-ins typed by a player cannot inject formatting, commands, or placeholder requests. Placeholders are text substitutions; do not use them in click-command/URL tag arguments. Built-ins `{player}`, `{server}`, `{message}`, and `{prefix}` work in chat; private messages also offer `{target}`. MiniMessage templates additionally accept `<player>`, `<server>`, `<message>`, `<prefix>`, and `<target>` as safe text tags.

PAPI lookups never block an event thread. Only configured expressions are requested, avoiding a unique cache entry for every user message. A dedicated bridge API instance uses `placeholders.cache-ms` (default 30 seconds), with bounded `placeholders.timeout-ms` and no retries. Missing, unresolved, oversized, failed, or timed-out values use `placeholders.fallback` (empty by default). Accepted messages are queued in sender order, with at most 64 pending messages per sender. Delivery rechecks active sessions, server/group restrictions, current permissions, and ignore/receiving preferences, so a slow lookup cannot bypass a subsequent privacy change.

Separate global/local, staff, and private cooldowns use atomic per-player checks and a monotonic clock. Global and local share a bucket. Blank messages, overlength messages, control characters, and invisible format controls are rejected. Limits count Unicode code points. Suggestions are capped. There is no social spy or message-content logging; admins with console access still control the proxy and its other plugins.

## Network player-name completion

With `suggestions.chat=true`, ordinary chat completes names across backend servers, respecting `networkchat.hidden` and `networkchat.see.hidden`. Names also complete inside `/g`, `/sc`, `/reply`, and `/msg` message bodies, in addition to private-message target completion. The network still means players connected to this one proxy.

- **1.19.1+ clients:** Velocity's custom chat-completion API supplies extra names without altering TAB.
- **1.13–1.19 clients:** Minecraft completes ordinary chat from its player list. `suggestions.legacy-tab-list=true` adds remote players to TAB using plugin-owned synthetic UUIDs, with a default cap of 500. It never overwrites backend entries. Set it to `false` if another plugin owns the entire TAB list; ordinary chat completion for remote players in that client range then needs that plugin to supply them.
- **1.7–1.12.2 clients:** Legacy tab-completion responses are augmented with network names.

Updates use deltas on a configurable `suggestions.refresh-ms` interval (default 2 seconds), plus server-change/disconnect refreshes. A permission change may take up to that interval to update the client's suggestions. Limits are independently configurable for command suggestions, custom chat suggestions, and legacy TAB entries. Filtering these extra suggestions does not remove a backend's own TAB entries; use your vanish plugin for those.

## Operation at scale

Message handling performs no disk, database, external HTTP, or blocking transport calls. Optional PAPI resolution uses asynchronous bridge requests. Public/staff fan-out is O(connected players), private target lookup uses Velocity's player directory, and one component is rendered per broadcast. Configuration loading runs on the proxy scheduler. A single scheduled reconciliation task maintains name-completion deltas; modern clients share immutable name catalogs, visibility permissions are evaluated once per player per refresh, and unchanged catalogs send no extra packets; legacy TAB fallback can require O(players²) recipient state on older clients, which is bounded by its separate limit. The plugin creates no runtime thread pools.

Preferences are **session-only**: selected channel, staff mode, receiving toggle, reply target, cooldowns, and ignored players reset when the owner disconnects or the proxy restarts. Ignore lists are capped at 1,000 entries per player and survive a target's reconnect within the viewer's session. Reply links clear when the conversation partner disconnects. This provides bounded state without storage I/O in the message path.

No throughput benchmark or real-client/backend compatibility matrix is claimed. CI verifies routing/security behavior with mocked players and actual proxy startup/console integration. Before deployment, run the [acceptance checklist](docs/ACCEPTANCE.md) on your actual client/backend mix, especially for signed chat.

## Build and releases

Build using JDK 17+ and Maven 3.9+:

```sh
mvn -B -ntp clean verify
```

The deployable shaded JAR is in `plugin/target/`. Do not deploy the `original-` JAR or the formatting module alone. Run `verify` from the repository root: it packages the isolated parser before running plugin tests. To check the latest API locally, use the matching JDK and `mvn -Dvelocity.api.version=4.2.0 clean verify`.

Every branch push and pull request runs the API/Java compatibility matrix and real-proxy smoke checks. After all checks pass, **every commit pushed to `main`** publishes a uniquely versioned prerelease with the JAR and `SHA256SUMS`. The JAR's plugin metadata and manifest contain that same version. Re-running a release job updates its existing release assets. No personal access token or separate release secret is required: publication uses GitHub's scoped workflow token. Third-party actions are pinned by commit SHA; pull requests receive no write permissions. Manual workflow dispatch is also supported.

MIT licensed. Contributions and bug reports are welcome; include proxy, Java, client, backend, and SignedVelocity versions plus your relevant configuration (no secrets).
