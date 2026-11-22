package org.lotro.conductor;

import java.io.IOException;
import java.util.*;

/** Linux X11/Wine focused game-input backend. Only sends to a verified foreground LOTRO window. */
public final class X11Backend implements GameBackend {
    @FunctionalInterface interface Runner {
        ProcessTools.Result run(int timeoutSeconds, String... args) throws Exception;
    }

    private final Runner command;
    public X11Backend() { this(ProcessTools::result); }
    X11Backend(Runner command) { this.command = command; }

    private static String runChecked(Runner runner, int seconds, String... args) throws Exception {
        ProcessTools.Result response = runner.run(seconds, args);
        if (response.exitCode() != 0)
            throw new IOException(args[0] + " failed (" + response.exitCode() + "): " + response.output().strip());
        return response.output();
    }

    /** A transient BadWindow while wmctrl enumerates UNRELATED desktop windows is not a hard failure.
     * A clean snapshot is preferred; if all attempts encounter the race, use the most complete
     * parseable partial snapshot rather than throwing raw wmctrl diagnostics at the musician.
     */
    @Override public List<GameWindow> listWindows() throws Exception {
        List<GameWindow> best = List.of();
        for (int attempt = 0; attempt < 3; attempt++) {
            ProcessTools.Result response = command.run(5, "wmctrl", "-l", "-p");
            List<GameWindow> found = parseWindowList(response.output());
            if (response.exitCode() == 0) return found;
            if (!response.output().contains("BadWindow"))
                throw new IOException("wmctrl listing failed (" + response.exitCode() + "): " + response.output().strip());
            if (found.size() > best.size()) best = found;
            if (attempt < 2) Thread.sleep(110);
        }
        return best;
    }

    static List<GameWindow> parseWindowList(String output) {
        List<GameWindow> windows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : output.split("\\R")) {
            String[] fields = line.strip().split("\\s+", 5);
            if (fields.length < 5 || !fields[0].matches("0x[0-9a-fA-F]+")) continue;
            String title = fields[4];
            if (!isGameTitle(title)) {
                String command = "";
                try {
                    long pid = Long.parseLong(fields[2]);
                    command = ProcessHandle.of(pid).flatMap(p -> p.info().command()).orElse("");
                } catch (NumberFormatException ignored) { }
                if (!isGameTitle(command)) continue;
            }
            if (seen.add(fields[0])) windows.add(new GameWindow(fields[0], title));
        }
        return List.copyOf(windows);
    }

    private static boolean isGameTitle(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("lord of the rings online") || lower.contains("lotroclient") || lower.contains("lotro.exe");
    }

    private static long xid(String id) throws IOException {
        if (!id.matches("0x[0-9a-fA-F]+")) throw new IOException("Not an X11 window ID: " + id);
        return Long.parseUnsignedLong(id.substring(2), 16);
    }

    /** Probes ONE registered XID. No global wmctrl enumeration and no accidental XID substitution. */
    @Override public boolean isValidWindow(GameWindow window) throws Exception {
        long requested = xid(window.id());
        ProcessTools.Result result = command.run(5, "xdotool", "getwindowname", Long.toUnsignedString(requested));
        if (result.exitCode() != 0) return false;
        return isGameTitle(result.output());
    }

    private void requireFocus(long expected) throws Exception {
        String activeText = runChecked(command, 5, "xdotool", "getactivewindow").strip();
        long active;
        try { active = Long.parseUnsignedLong(activeText); }
        catch (NumberFormatException e) { throw new IOException("Cannot parse current X11 focus: " + activeText, e); }
        if (active != expected)
            throw new IOException("Refusing to type: foreground X11 window " + activeText +
                    " differs from registered LOTRO window " + Long.toUnsignedString(expected));
    }

    @Override public void focusWindow(GameWindow window) throws Exception {
        long requested = xid(window.id());
        if (!isValidWindow(window))
            throw new IOException("Registered X11 LOTRO window has disappeared or changed; refresh and register again");
        runChecked(command, 5, "wmctrl", "-ia", window.id());
        Thread.sleep(180);
        requireFocus(requested);
    }

    @Override public void sendChat(GameWindow window, String text) throws Exception {
        long requested = xid(window.id());
        focusWindow(window);
        runChecked(command, 5, "xdotool", "key", "--clearmodifiers", "Return");
        requireFocus(requested);
        runChecked(command, 10, "xdotool", "type", "--clearmodifiers", "--delay", "3", "--", text);
        // Never submit the chat line if focus changed during typing.
        requireFocus(requested);
        runChecked(command, 5, "xdotool", "key", "--clearmodifiers", "Return");
    }

    @Override public void sendShortcut(GameWindow window, Shortcut shortcut) throws Exception {
        long requested = xid(window.id());
        focusWindow(window);
        requireFocus(requested);
        runChecked(command, 5, "xdotool", "key", "--clearmodifiers", shortcut.x11());
        requireFocus(requested);
    }
    @Override public String description() { return "Linux X11 (wmctrl + xdotool; direct window checks)"; }
}
