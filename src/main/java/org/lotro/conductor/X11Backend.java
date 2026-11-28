package org.lotro.conductor;

import java.io.IOException;
import java.util.*;
import java.awt.datatransfer.Clipboard;
import java.util.function.Supplier;

/** Linux X11/Wine focused game-input backend. Only sends to a verified foreground LOTRO window. */
public final class X11Backend implements GameBackend {
    @FunctionalInterface interface Runner {
        ProcessTools.Result run(int timeoutSeconds, String... args) throws Exception;
    }

    private final Runner command;
    private final LauncherExclusion launcherExclusion=new LauncherExclusion();
    @Override public void setLauncherWindow(LauncherWindow launcher){launcherExclusion.set(launcher);}
    @Override public boolean isSelectedLauncher(GameWindow window){return launcherExclusion.x11(window.id());}
    private final Supplier<Clipboard> clipboard;
    private volatile ChatPastePolicy pastePolicy = ChatPastePolicy.DEFAULT;
    private volatile String lastChatMethod = "unspecified";
    @Override public String lastChatInputMethod() { return lastChatMethod; }
    @Override public void setChatPastePolicy(ChatPastePolicy policy) { pastePolicy = Objects.requireNonNull(policy); }
    private volatile ChatInputTiming chatTiming = ChatInputTiming.DEFAULT;
    @Override public void setChatTiming(ChatInputTiming timing) { chatTiming = Objects.requireNonNull(timing); }
    public X11Backend() { this(ProcessTools::result, ChatClipboard::desktopClipboard); }
    X11Backend(Runner command) { this(command, ChatClipboard::desktopClipboard); }
    X11Backend(Runner command, Supplier<Clipboard> clipboard) {
        this.command = command; this.clipboard = clipboard;
    }

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
            LauncherWindow exclusion=launcherExclusion.current();
            ProcessTools.Result response = command.run(5, "wmctrl", "-l", "-p");
            List<GameWindow> found = parseWindowList(response.output());
            if (response.exitCode() == 0) {
                launcherExclusion.observeX11(exclusion,response.output());
                return withoutLaunchers(found);
            }
            if (!response.output().contains("BadWindow"))
                throw new IOException("wmctrl listing failed (" + response.exitCode() + "): " + response.output().strip());
            if (found.size() > best.size()) best = found;
            if (attempt < 2) Thread.sleep(110);
        }
        return withoutLaunchers(best);
    }

    /** The launcher sometimes has the same title as a game client. Use the
     * X11 class as an additional exclusion, without making xprop a hard
     * dependency for existing game-only installations. */
    private boolean knownLauncher(GameWindow window) throws Exception {
        if(isSelectedLauncher(window))return true;
        try{
            ProcessTools.Result r=command.run(5,"xprop","-id",window.id(),"WM_CLASS","_NET_WM_PID");
            return r.exitCode()==0&&LauncherPlatform.launcherClass(r.output());
        }catch(InterruptedException e){throw e;}
        catch(IOException e){return false;}
    }
    private List<GameWindow> withoutLaunchers(List<GameWindow> windows)throws Exception {
        List<GameWindow> result=new ArrayList<>();
        for(GameWindow w:windows)if(!knownLauncher(w))result.add(w);
        return List.copyOf(result);
    }

    static List<GameWindow> parseWindowList(String output) {
        List<GameWindow> windows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : output.split("\\R")) {
            String[] fields = line.strip().split("\\s+", 5);
            if (fields.length < 5 || !fields[0].matches("0x[0-9a-fA-F]+")) continue;
            String title = fields[4];
            if (title.toLowerCase(Locale.ROOT).contains("launcher")) continue;
            try { if(LauncherPlatform.launcherProcess(Long.parseLong(fields[2]))) continue; }
            catch(NumberFormatException ignored) { }
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
        if(lower.contains("launcher"))return false;
        return lower.contains("lord of the rings online") || lower.contains("lotroclient") || lower.contains("lotro.exe");
    }

    private static long xid(String id) throws IOException {
        if (!id.matches("0x[0-9a-fA-F]+")) throw new IOException("Not an X11 window ID: " + id);
        return Long.parseUnsignedLong(id.substring(2), 16);
    }

    /** Probes ONE registered XID. No global wmctrl enumeration and no accidental XID substitution. */
    @Override public boolean isValidWindow(GameWindow window) throws Exception {
        if(isSelectedLauncher(window))return false;
        long requested = xid(window.id());
        ProcessTools.Result result = command.run(5, "xdotool", "getwindowname", Long.toUnsignedString(requested));
        if (result.exitCode() != 0) return false;
        return isGameTitle(result.output()) && !knownLauncher(window);
    }

    private void requireFocus(long expected) throws Exception {
        if(launcherExclusion.x11("0x"+Long.toUnsignedString(expected,16)))
            throw new IOException("Selected launcher is excluded from game input");
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

    /** xdotool can temporarily remap an absent keysym. Ask the X server which
     * modifiers actually exist first, so recovery never requests absent keys.
     * AltGr is represented by ISO_Level3_Shift on common X11 layouts. */
    static List<String> mappedModifiers(String map) {
        Set<String> available = new HashSet<>();
        var matcher = java.util.regex.Pattern.compile(
                "\\b(Shift_[LR]|Control_[LR]|Alt_[LR]|ISO_Level3_Shift)\\s*\\(0x[0-9a-fA-F]+\\)").matcher(map);
        while (matcher.find()) available.add(matcher.group(1));
        return List.of("Shift_L","Shift_R","Control_L","Control_R","Alt_L","Alt_R","ISO_Level3_Shift")
                .stream().filter(available::contains).toList();
    }
    private String releaseModifiers(long requested, boolean required) throws Exception {
        List<String> modifiers;
        try {
            modifiers = mappedModifiers(runChecked(command, 5, "xmodmap", "-pm"));
        } catch (InterruptedException e) { throw e; }
        catch (Exception e) {
            if (required) throw new IOException("Cannot read X11 modifier map; install xmodmap and retry", e);
            return "modifier recovery skipped: xmodmap unavailable";
        }
        if (modifiers.isEmpty()) {
            if (required) throw new IOException("No recognized Ctrl/Shift/Alt keys in X11 modifier map");
            return "modifier recovery skipped: no recognized keys";
        }
        requireFocus(requested);
        // Key-up only. --clearmodifiers would restore the previous held state.
        List<String> args = new ArrayList<>(List.of("xdotool", "keyup"));
        args.addAll(modifiers);
        runChecked(command, 5, args.toArray(String[]::new));
        Thread.sleep(80);
        requireFocus(requested);
        return "";
    }

    @Override public void resetKeyboard(GameWindow window) throws Exception {
        focusWindow(window);
        releaseModifiers(xid(window.id()), true);
    }

    @Override public void sendChat(GameWindow window, String text) throws Exception {
        // LocalAgent serializes all window actions. Keep the clipboard lease
        // through post-submit settling to avoid pasting restored clipboard data.
        ChatInputTiming timing = chatTiming;
        long requested = xid(window.id());
        focusWindow(window);
        String recoveryNote = releaseModifiers(requested, false);
        ChatPastePolicy policy = pastePolicy;
        ChatClipboard.Attempt attempt = policy.shouldPaste(text)
                ? ChatClipboard.tryLease(clipboard, text, policy.preserveClipboard()) : null;
        ChatClipboard.Lease lease = attempt == null ? null : attempt.lease();
        lastChatMethod = attempt == null ? "typed" : attempt.method();
        if (!recoveryNote.isEmpty()) lastChatMethod += " (" + recoveryNote + ")";
        try {
            requireFocus(requested);
            runChecked(command, 5, "xdotool", "key", "--clearmodifiers", "Return");
            Thread.sleep(timing.chatOpenMs());
            if (lease != null) {
                // If another application replaced the clipboard after our lease,
                // abort rather than accidentally pasting somebody else's text.
                if (!lease.matchesCurrent())
                    throw new IOException("Clipboard changed or became unreadable before Ctrl+V; chat not submitted");
                requireFocus(requested);
                runChecked(command, 5, "xdotool", "key", "--clearmodifiers", "ctrl+v");
                // X11 clipboard selection and Wine need time to deliver the paste.
                Thread.sleep(Math.max(350, timing.beforeEnterMs()));
            } else {
                requireFocus(requested);
                runChecked(command, timing.typeTimeoutSeconds(text.length()), "xdotool", "type",
                        "--clearmodifiers", "--delay", Integer.toString(timing.keyDelayMs()), "--", text);
                Thread.sleep(timing.beforeEnterMs());
            }
            requireFocus(requested);
            runChecked(command, 5, "xdotool", "key", "--clearmodifiers", "Return");
            // Paste is one shortcut, not N queued key events: use a minimum
            // clipboard/submit settle period rather than per-character delay.
            Thread.sleep(lease == null ? timing.afterEnterDelayMs(text)
                    : Math.max(350, timing.afterEnterMs()));
        } finally {
            if (lease != null) {
                lease.close();
                if (!lease.warning().isEmpty()) lastChatMethod += " (" + lease.warning() + ")";
            }
        }
    }

    @Override public void sendShortcut(GameWindow window, Shortcut shortcut) throws Exception {
        long requested = xid(window.id());
        focusWindow(window);
        String recoveryNote = releaseModifiers(requested, false);
        if (!recoveryNote.isEmpty()) System.err.println(recoveryNote);
        requireFocus(requested);
        runChecked(command, 5, "xdotool", "key", "--clearmodifiers", shortcut.x11());
        Thread.sleep(120);
        requireFocus(requested);
    }
    @Override public String description() { return "Linux X11 (wmctrl + xdotool; direct window checks)"; }
}
