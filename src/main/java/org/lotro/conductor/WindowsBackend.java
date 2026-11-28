package org.lotro.conductor;

import java.io.IOException;
import java.util.*;

/** Windows desktop input: verify the foreground PID, not merely AppActivate's success. */
public final class WindowsBackend implements GameBackend {
    @FunctionalInterface interface Runner { String run(int timeoutSeconds, String... args) throws Exception; }
    private final Runner runner;
    private final LauncherExclusion launcherExclusion=new LauncherExclusion();
    @Override public void setLauncherWindow(LauncherWindow launcher){launcherExclusion.set(launcher);}
    @Override public boolean isSelectedLauncher(GameWindow window){return launcherExclusion.windows(window.id());}
    public WindowsBackend() { this(ProcessTools::run); }
    WindowsBackend(Runner runner) { this.runner = runner; }

    // P/Invoke runs inside the existing Windows PowerShell dependency. No extra JAR
    // or native DLL needs to be distributed. Left/right VKs are explicit; right
    // Ctrl and Alt use the extended-key flag. We send releases, never keydowns.
    private static final String NATIVE = """
        $ErrorActionPreference = 'Stop'
        Add-Type -TypeDefinition @'
        using System;
        using System.Runtime.InteropServices;
        public static class ConductorKeyboard {
            [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
            [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
            [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint mapType);
            [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
            public static uint ForegroundPid() {
                uint pid;
                GetWindowThreadProcessId(GetForegroundWindow(), out pid);
                return pid;
            }
            public static bool ReleaseModifiers(uint expected) {
                foreach (byte vk in new byte[] {0xA0,0xA1,0xA2,0xA3,0xA4,0xA5}) {
                    if (ForegroundPid() != expected) return false;
                    keybd_event(vk, (byte)MapVirtualKey(vk, 0), (uint)(2 | ((vk == 0xA3 || vk == 0xA5) ? 1 : 0)), UIntPtr.Zero);
                }
                return ForegroundPid() == expected;
            }
        }
        '@
        """;
    private volatile ChatInputTiming chatTiming = ChatInputTiming.DEFAULT;
    private volatile ChatPastePolicy pastePolicy = ChatPastePolicy.DEFAULT;
    @Override public String lastChatInputMethod() { return KeyTyper.lastChatInputMethod(); }
    @Override public void setChatPastePolicy(ChatPastePolicy policy) { pastePolicy = Objects.requireNonNull(policy); }
    @Override public void setChatTiming(ChatInputTiming timing) { chatTiming = Objects.requireNonNull(timing); }

    private String ps(String script) throws Exception {
        return runner.run(12, "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
    }
    private static String pid(GameWindow window) throws IOException {
        if (!window.id().matches("[0-9]+")) throw new IOException("Not a Windows process ID");
        try {
            long id = Long.parseLong(window.id());
            if (id <= 0 || id > Integer.MAX_VALUE) throw new NumberFormatException();
            return Long.toString(id);
        } catch (NumberFormatException e) { throw new IOException("Invalid Windows process ID"); }
    }
    @Override public List<GameWindow> listWindows() throws Exception {
        String raw = ps("Get-Process | Where-Object { $_.ProcessName -match '^lotroclient' -and $_.MainWindowHandle -ne 0 } | ForEach-Object { [string]$_.Id + '|' + $_.MainWindowTitle }");
        List<GameWindow> windows = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            int pipe = line.indexOf('|');
            if (pipe < 1) continue;
            String id = line.substring(0, pipe).trim();
            if (id.matches("[0-9]+")) {
                GameWindow window=new GameWindow(id,line.substring(pipe+1).trim());
                if(!isSelectedLauncher(window))windows.add(window);
            }
        }
        return windows;
    }
    /** Fail closed on foreground mismatch before every distinct input phase. */
    void requireFocus(GameWindow window) throws Exception {
        if(isSelectedLauncher(window))throw new IOException("Selected launcher is excluded from game input");
        String expected = pid(window);
        String active = ps(NATIVE + "\n[ConductorKeyboard]::ForegroundPid()").strip();
        if (!active.equals(expected))
            throw new IOException("Refusing Windows input: intended LOTRO process " + expected + " is not foreground");
    }
    @Override public void focusWindow(GameWindow window) throws Exception {
        if(isSelectedLauncher(window))throw new IOException("Selected launcher is excluded from game input");
        String expected = pid(window);
        String script = "$ErrorActionPreference = 'Stop'; $p = Get-Process -Id " + expected +
                "; if ($p.ProcessName -notmatch '^lotroclient' -or $p.MainWindowHandle -eq 0) { exit 4 }; " +
                "$w = New-Object -ComObject WScript.Shell; if (-not $w.AppActivate(" + expected + ")) { exit 3 }";
        ps(script);
        Thread.sleep(180);
        requireFocus(window);
    }
    private void releaseModifiers(GameWindow window) throws Exception {
        String expected = pid(window);
        if (!ps(NATIVE + "\n[ConductorKeyboard]::ReleaseModifiers(" + expected + ")").strip().equalsIgnoreCase("True"))
            throw new IOException("Windows foreground changed during keyboard reset; no further input sent");
        Thread.sleep(80);
    }
    @Override public void resetKeyboard(GameWindow window) throws Exception {
        focusWindow(window);
        releaseModifiers(window);
        requireFocus(window);
    }
    @Override public void sendChat(GameWindow window, String command) throws Exception {
        focusWindow(window);
        releaseModifiers(window);
        KeyTyper.command(command, chatTiming, pastePolicy, () -> requireFocus(window));
    }
    @Override public void sendShortcut(GameWindow window, Shortcut shortcut) throws Exception {
        focusWindow(window);
        releaseModifiers(window);
        KeyTyper.Keys keys = KeyTyper.robotKeys(40);
        requireFocus(window);
        KeyTyper.shortcut(keys, shortcut);
        Thread.sleep(120);
        requireFocus(window);
    }
    @Override public String description() { return "Windows (verified foreground PID, modifier release, AWT Robot; US layout)"; }
}
