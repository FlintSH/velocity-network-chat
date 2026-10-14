# Bundled libraries

The formatting module isolates these libraries under `dev.flint.networkchat.formatting.lib`; no proxy Adventure classes are bundled in their original namespace.

| Libraries | Authors | License |
| --- | --- | --- |
| Adventure, Adventure Key, MiniMessage, text serializers, Examination | Kyori/PaperMC contributors | MIT |
| Gson, Auto Service annotations, Error Prone annotations | Google contributors | Apache-2.0 |
| Java annotations | JetBrains contributors | Apache-2.0 |

Full license text and attribution are included in the JAR's `META-INF/networkchat-licenses/` directory. Dependencies' versions are pinned in the formatting POM and resolved transitively from those libraries. PAPIProxyBridge and Velocity are optional/provided integrations and are not bundled.
