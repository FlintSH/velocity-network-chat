# Velocity Network Chat

[![Test and release](https://github.com/FlintSH/velocity-network-chat/actions/workflows/release.yml/badge.svg)](https://github.com/FlintSH/velocity-network-chat/actions/workflows/release.yml)

Cross-server chat, a separate permission-gated staff channel, and private messaging for a Velocity network. One lightweight JAR, no bundled libraries, no database, no telemetry, and no message logging.

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
4. For ordinary cross-server chat on modern clients, follow the signed-chat setup below. `/g`, `/sc`, and `/msg` work without that integration.
5. Edit the configuration and use `/networkchat reload` from the proxy console or as an administrator.

## Compatibility and signed chat

The published JAR targets Java 17 and the Velocity **3.1.1 API**. CI tests APIs 3.1.1, 3.4.0, and 4.2.0, and starts real proxies with the same baseline JAR. Use the Java version required by your proxy: the plugin's Java 17 bytecode does not lower Velocity's own requirements.

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
| `/sc <message>` (`/staffchat`) | Staff-only message across all connected backends |
| `/msg <player> <message>` (`/tell`, `/whisper`, `/w`) | Private message across backend servers |
| `/r <message>` (`/reply`) | Reply to the last successful private conversation |
| `/ignore <player>` | Toggle ignoring public and private messages from an online player |
| `/msgtoggle` | Toggle receipt of private messages |
| `/chat global` or `/chat local` | Select routing for ordinary chat where capture is safe |
| `/networkchat reload` (`/nchat`) | Validate and atomically reload configuration |
| `/networkchat status` | Show connected players, session count, and integration state |

Staff chat is deliberately an explicit command. Ordinary chat never switches into staff mode, avoiding accidental disclosure when moving between servers with different signed-chat support. Staff recipients are selected using `staff-receive` for each delivery; removing permission takes effect immediately. Staff messages are not suppressed by player ignore preferences. The sender needs both staff send and receive permission.

Private/staff command aliases are claimed by the proxy even for players without permission, so denied messages are not forwarded to backend commands. Alias collisions with other proxy plugins abort initialization without intercepting chat. A startup failure is prominently logged; backend ordinary chat continues.

## Configuration

Every format, response, command alias, and permission is configurable in the [annotated default configuration](src/main/resources/config.properties). It is UTF-8 Java Properties: use commas for lists, `true`/`false` for booleans, and escape literal backslashes as `\\`. Unknown keys, unsafe privileged permissions, invalid values, duplicate aliases, and unsupported config versions are rejected. Missing keys inherit bundled defaults, so existing configuration remains usable when defaults are extended. The plugin does not rewrite your file on reload.

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

Templates support legacy `&` color/style codes. `{player}`, `{server}`, and `{message}` are available for public/staff messages; private formats also support `{target}`. Each response's available tokens are shown in the defaults. User input is inserted as literal text after parsing formatting, so color codes, MiniMessage tags, and nested placeholders cannot inject formatting or commands. Administrator-owned templates are parsed once per configuration snapshot.

Separate global/local, staff, and private cooldowns use atomic per-player checks and a monotonic clock. Global and local share a bucket. Blank messages, overlength messages, control characters, and invisible format controls are rejected. Limits count Unicode code points. Suggestions are capped. There is no social spy or message-content logging; admins with console access still control the proxy and its other plugins.

## Operation at scale

Message handling performs no disk, database, external HTTP, or blocking transport calls. Public/staff fan-out is O(connected players), private target lookup uses Velocity's player directory, and one component is rendered per broadcast. Configuration loading runs on the proxy scheduler. No recurring polling tasks or extra runtime thread pools are created.

Preferences are **session-only**: selected channel, receiving toggle, reply target, cooldowns, and ignored players reset when the owner disconnects or the proxy restarts. Ignore lists are capped at 1,000 entries per player and survive a target's reconnect within the viewer's session. Reply links clear when the conversation partner disconnects. This provides bounded state without storage I/O in the message path.

No throughput benchmark or real-client/backend compatibility matrix is claimed. CI verifies routing/security behavior with mocked players and actual proxy startup/console integration. Before deployment, run the [acceptance checklist](docs/ACCEPTANCE.md) on your actual client/backend mix, especially for signed chat.

## Build and releases

Build using JDK 17+ and Maven 3.9+:

```sh
mvn -B -ntp clean verify
```

The JAR is in `target/`. To check the latest API locally, use the matching JDK and `mvn -Dvelocity.api.version=4.2.0 clean verify`.

Every branch push and pull request runs the API/Java compatibility matrix and real-proxy smoke checks. After all checks pass, **every commit pushed to `main`** publishes a uniquely versioned prerelease with the JAR and `SHA256SUMS`. The JAR's plugin metadata and manifest contain that same version. Re-running a release job updates its existing release assets. No personal access token or separate release secret is required: publication uses GitHub's scoped workflow token. Third-party actions are pinned by commit SHA; pull requests receive no write permissions. Manual workflow dispatch is also supported.

MIT licensed. Contributions and bug reports are welcome; include proxy, Java, client, backend, and SignedVelocity versions plus your relevant configuration (no secrets).
