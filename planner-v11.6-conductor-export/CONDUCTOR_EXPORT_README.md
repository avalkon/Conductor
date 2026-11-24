# SongBook Planner v11.6 — Conductor JSON export patch

The original Planner's web UI (used only for planning/exporting, **not** inside Conductor) now has **Export Conductor (.json)** alongside its existing Setlist ODS export. The endpoint is handled locally by `songbook_planner.py`; no new Python packages are required for the JSON export. Existing ODS export still depends on `odfpy` as before.

**Path fix (for v0.6.0 exports):** When the SongBook library file path contains a `songs` directory, the export now retains that segment in `gamePath`. Thus `<Music>/songs/Condensed Music/2 Parts/Amaranth2.abc` becomes `/songs/Condensed Music/2 Parts/Amaranth2` (without `.abc`). Letter case is retained for Linux. Previous exports can be re-exported or opened through Conductor 0.6.1's specific legacy fallback.

The JSON `format` is `lotro-conductor-setlist`, `version` 1, with ordered `songs`, a case-preserving extensionless `gamePath`, and assigned `parts` with original `trackId` (from SongBook `Tracks[*].Id`), LOTRO `character`, and performed `instrument` (Planner's `playedAs` substitution, if configured). `unassignedTrackIds` is informational.

Ensure Planner member names are the same as actual LOTRO characters (no spaces) and all assigned parts have valid original SongBook X: IDs. Export fails with an explicit error rather than inventing a player or a part number. Only folders/files supported by Conductor's current ABC path rules can be exported.

Run offline tests:

```bash
python3 -m unittest -v test_conductor_export
```

To use, run Planner normally using the supplied platform launcher scripts. Export your setlist JSON and load it in Conductor 0.6.1 through **Music & performance → Import**. The exporter changes no SongBook Lua files or PluginData settings.
