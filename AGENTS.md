# Minecraft plugin delivery

For requested work in this repository, commit directly to `main` and push after
validation. Do not open pull requests unless the user explicitly asks for one.
This is the user's standing preference for these Minecraft plugins, recorded on
2026-10-17. A passing `main` pipeline publishes the release; verify the pipeline
and released artifacts before reporting delivery complete.

Use an isolated task worktree, preserve unrelated changes, and follow the
documented Velocity compatibility matrix and Paper bridge probe. Direct delivery
does not authorize installing plugins on the production Minecraft network.
