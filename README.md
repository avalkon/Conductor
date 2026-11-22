<p align="center">
<a href="https://github.com/avalkon/Conductor/releases"><img src="https://img.shields.io/github/downloads/avalkon/Conductor/total?label=Downloads" alt="Downloads" /></a>
 <img src="https://img.shields.io/badge/Windows-supported-0078D6?logo=windows&logoColor=white" alt="Windows" />
  <img src="https://img.shields.io/badge/Linux-supported-FCC624?logo=linux&logoColor=white" alt="Linux" />
</p>

# LOTRO Conductor v0.12.5

Conductor is a LotRO plug-beside intended for use in conjunction with SongBook3. SongBook3 versions 1.25.5 and up are ready-to-use with Conductor. Should be linux+windows compatible, but only tested in linux so far, specifically in x11. Wayland needs testing and development, and I don't have any machines using it. Windows testing to come.

Two or more different characters may now intentionally share the same ABC `X:` part. In previous versions, **Sync All** and **Show in SongBook** aborted with `Two or more characters are assigned the same X: track ID`.

- The Music tab marks shared tracks **[shared: approval needed]**.
- When sending Sync All or Show in SongBook, the controlling Conductor shows the shared track ID/title and every assigned musician, including their computer. **Continue with shared parts** explicitly approves that exact song and assignment layout; **Cancel** sends nothing.
- Approval is kept for the current application session, so Show and Sync do not repeatedly ask for the identical arrangement. Choosing a different song or changing assignments invalidates that approval; reassigning them back still requires new approval.
- This important ambiguity prompt appears **even if regular confirmation prompts are disabled**. Normal command-confirmation settings otherwise remain unchanged.

- Conductor's Planner setlist reader permits different characters to use the same `trackId`. It rejects repeated character names within one song, unknown track IDs, or missing characters.

Conductor approval only controls Conductor's duplicate-track safety check. SongBook's own Sync Check window will continue to warn about double assignments independently.

The two optional *Refocus Conductor after...* settings explicitly **raise the host Conductor window** as well as request activation. v0.12.3 used only Swing `JFrame.toFront()`/`requestFocus()`, which can focus the window without moving it above LOTRO in some X11 window managers.

The new X11 helper queries `wmctrl -l -p` for this Java process's **unique exact window title and PID**, verifies both against `xdotool` immediately before use, then calls `wmctrl -ia <XID>` and `xdotool windowraise <XID>`. If the identity is missing, ambiguous, or changed, it does not raise any other window; it logs the failure and retains Swing's best-effort activation. It **never** guesses based on a substring title, changes system/window-manager settings, or applies a persistent always-on-top flag.

The X11 helper runs outside the Swing event thread. It still starts **after the host receives all action completion results**. The separate *Refocus selected leader after Sync All* preference is unchanged; Sync All stays excluded from the new host-refocus options. The update adds no network message types, protocol fields, or Lua changes.

Two independent Settings options can bring the **host Conductor** window back to the front *after* game input finishes across the network:

- **Refocus Conductor after /music, Equip, and Space** — for those three main actions (including all-musician dispatch and auto-equip).
- **Refocus Conductor after other commands (except Sync All)** — for `/playstart`, Follow, Show in SongBook, custom commands, and Report.

The two options default to **off**, are saved independently, and are only enabled in the master GUI. **Sync All is excluded from both**; its separate **Refocus selected leader window after Sync All** option remains intact. The focus request uses standard Swing window activation.

The host waits for per-action completion messages before refocusing rather than immediately taking focus after enqueuing network actions. Each message carries a unique correlation ID so asynchronous results arriving out of order are handled correctly. If acknowledgments are missing, a 120-second fallback reports the missing count and returns focus to the host window. This is about completion of game input dispatch, **not verification of in-game state**.


The master Settings panel includes **Refocus selected leader window after Sync All** (OFF by default). Once the master has dispatched Sync All to the band, it queues a focus-only operation for the registered leader's window on whichever computer owns it. The operation is serialized behind that computer's earlier song-preparation commands, so it should work with remote Linux and Windows agents and never sends additional game keys. Inability to focus returns a failure rather than falling back to a different window.

The leader must be selected in the shared leader dropdown. When no leader is selected, Sync All proceeds normally but refocus is skipped. This feature neither waits for SongBook readiness nor changes music mode. The setting is persisted on the master, and the control is disabled on follower instances.


Settings on the host now includes **Equip: skip characters whose last successful quickslot was the same instrument**. When enabled, agents skip repeated instrument shortcut commands for each local character and report `EQUIP SKIPPED: unchanged`. Saved shortcut changes still require a new keypress. The setting is OFF by default.

**Recovery:** Settings also has **Reset remembered Equip shortcuts (all computers)**. This resets every registered character's transient shortcut history without sending game input. The next Equip sends the shortcut. History also resets on re-registering a character or restarting its agent.

---

## Networking details

- **Distributed Assign All**: each agent announces the instrument **names** for which its registered characters have saved quickslot mappings. Only the host selects parts, considering the combined roster and preferring previous-song instrument continuity. Shortcut keys are **not sent over the network**; each computer owns and stores them locally.
- **Distributed Equip**: for the currently loaded ABC song or imported Planner setlist, the master sends an `EQUIP` request identifying the character and required instrument. The owning agent looks up its saved keybinding and presses it in its *own* verified game window if the last quickslot was a different instrument.
- **Space (All)**: compact button to the right of the custom commands, near the bottom of Music. Sends one Space keypress per registered window, including the leader, through the per-machine serialized input queue. It **does not claim** to prove that music mode has been exited; it may instead affect the active UI/chat focus in LOTRO.
- **Updated capabilities after changes**: editing an instrument quickslot on a registered character republishes the full list. Deleting an assignment removes that instrument from subsequent matching. Empty capability lists are distinguishable from agents that have not yet reported.
- **Fail-closed remote input**: missing mapping or changed/invalid window causes a `FAILED` result, never falls back to a different window or guessed shortcut.

## Requirements

- Java 17 or later and Maven (to compile source, not required to just use).
- On Linux: X11, `wmctrl` and `xdotool` installed and available on PATH for `--live`.
- Same release on **both/all connected computers**.
- Two network-connected computers with the same relative ABC song paths and content when using Sync All; there is no ABC file transfer.
- An existing compatible SongBook bridge, as used in v0.10.x/v0.11.0. **No Lua update is necessary.**

## Build

From the project directory:

```bash
mvn clean package
java -jar target/conductor.jar --self-test
```

## Launch host (computer A)

```bash
java -jar target/conductor.jar --host --name LinuxA --key 'choose-a-private-lan-secret' --live --music-dir '/path/to/Music'
```

## Join follower (computer B)

```bash
java -jar target/conductor.jar --join HOST_LAN_IP --name LinuxB --key 'choose-a-private-lan-secret' --live --music-dir '/path/to/Music'
```

Change `HOST_LAN_IP` to computer A's LAN address. The default port is TCP 38791 and both machines use the same key. The host controls songs; each follower registers its own LOTRO windows on **This computer**. The app is intended for a trusted LAN, not a publicly exposed service. If testing without a game, use `--demo` instead of `--live`.

Local-only `--local` operation if only running on one pc.

## Important distinctions

- `EQUIP SENT` means the shortcut was dispatched, **not** that the game confirmed equipment state.
- The Space command is **one keypress**.
- Each instrument requires an exact, locally saved quickslot binding on the relevant character. Mappings are never copied from the host.
- All commands remain restricted to authenticated connected computers and targeted registered LOTRO windows. The master is the only node permitted to issue band actions.

