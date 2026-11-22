# Conductor 0.4.0 — architecture notes

- **Java Swing** client and per-computer agent; no browser or per-game-client Java process.
- **Local game backend:** X11 uses `wmctrl` and `xdotool`, validating one registered window ID before input. Windows backend remains available but is not fully validated in-game.
- **Bridge protocol:** `CG1` reports remain accepted. `CG2%name%instrument%gamepath%part%music-observation%readiness%token%` reports add states and a random token. Strings are stored via `Turbine.PluginData.Save(Turbine.DataScope.Character, "ConductorStatus", report, callback)` and read by Java.
- **File monitoring:** `WatchService` event notification with 6-second fallback, bounded read and per-character name check.
- **Identity challenge:** user explicitly selects `PluginData` folder, presses GUI button, receives unique 128-bit nonce per verified unregistered window. Agent sends `/conductor identify <nonce>` and looks for exactly one report with that token under `PluginData` (bounded depth). It never infers character from title or uses an old window ID.
- **Music/readiness:** SongBook's local observed music mode, local player's ready-chat event and selection state; no claim of an authoritative game status API. The coordinator is not allowed to start playback automatically from an unverified or stale readiness report.
- **Network:** existing authenticated, bounded command protocol retained unchanged; cross-computer testing can resume after local validation.
