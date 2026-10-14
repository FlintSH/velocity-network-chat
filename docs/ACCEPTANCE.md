# Deployment acceptance

Automated tests cover business rules and console integration. These checks need actual Minecraft clients and your backend plugins; perform them on a staging proxy before production.

1. Join two different backends with two player accounts. Send `/g hello`; verify each participant sees exactly one message and the backend does not see a forwarded command.
2. Test ordinary chat with an older client and a signed modern client. For every acknowledged modern backend, install the matching SignedVelocity companion and verify one delivery, no backend duplicate, no kick, and no signed-chain warning after several messages and server switches.
3. Remove a server from `chat.signed-backends` and reload. Modern ordinary chat must stay backend-local; `/g` still works. Never acknowledge a backend without its companion.
4. Use staff send+receive, receive-only, and non-staff accounts. `/sc secret` must only reach staff receivers. Denied staff/private commands must not fall through to backend commands. Revoke receive permission and verify delivery stops immediately.
5. Verify `/msg` and `/r` across servers, offline target handling, `/ignore`, `/msgtoggle`, disconnect/reconnect reply clearing, hidden permission lookup, and suggestions.
6. Verify routing groups, local chat, excluded servers, cooldowns, long/Unicode messages, and malicious formatting input. Check interactions with your moderation/vanish plugins.
7. Reload a valid configuration, then an invalid one. The latter must report an error and leave the previous routing active. Restart after changing aliases; inspect logs for command collisions.
8. Load-test at your expected concurrent players and message rate. Monitor proxy CPU, event timings, and outbound bandwidth; broadcast work grows with connected recipients. This release connects backend servers on a single proxy only.
