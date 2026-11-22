package org.lotro.conductor;

import java.io.IOException;
import java.util.*;

/** Windows developer backend: per-client PID enumeration; focus via Windows Shell AppActivate. */
public final class WindowsBackend implements GameBackend {
    @Override public List<GameWindow> listWindows() throws Exception {
        String ps = "Get-Process | Where-Object { $_.ProcessName -match '^lotroclient' -and $_.MainWindowHandle -ne 0 } | ForEach-Object { [string]$_.Id + '|' + $_.MainWindowTitle }";
        String raw = ProcessTools.run(12, "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps);
        List<GameWindow> windows = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            int pipe = line.indexOf('|');
            if (pipe < 1) continue;
            String pid = line.substring(0, pipe).trim();
            if (pid.matches("[0-9]+")) windows.add(new GameWindow(pid, line.substring(pipe + 1).trim()));
        }
        return windows;
    }
    @Override public void focusWindow(GameWindow window) throws Exception {
        if (!window.id().matches("[0-9]+")) throw new IOException("Not a Windows process ID");
        String script = "$w = New-Object -ComObject WScript.Shell; if (-not $w.AppActivate(" + window.id() + ")) { exit 3 }";
        ProcessTools.run(12, "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
        Thread.sleep(180);
    }
    @Override public void sendChat(GameWindow window, String command) throws Exception {
        focusWindow(window);
        KeyTyper.command(command);
    }
    @Override public void sendShortcut(GameWindow window, Shortcut shortcut) throws Exception {
        if (!isValidWindow(window)) throw new IOException("Registered LOTRO window is no longer available");
        focusWindow(window);
        java.awt.Robot robot = new java.awt.Robot();
        robot.setAutoDelay(40);
        java.util.List<Integer> held = new ArrayList<>();
        try {
            if (shortcut.ctrl()) { robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL); held.add(java.awt.event.KeyEvent.VK_CONTROL); }
            if (shortcut.alt()) { robot.keyPress(java.awt.event.KeyEvent.VK_ALT); held.add(java.awt.event.KeyEvent.VK_ALT); }
            if (shortcut.shift()) { robot.keyPress(java.awt.event.KeyEvent.VK_SHIFT); held.add(java.awt.event.KeyEvent.VK_SHIFT); }
            int code = shortcut.keyCode();
            robot.keyPress(code);
            try { Thread.sleep(65); } finally { robot.keyRelease(code); }
        } finally { for (int i=held.size()-1;i>=0;i--) robot.keyRelease(held.get(i)); }
    }
    @Override public String description() { return "Windows via AppActivate(PID) and AWT Robot (US keyboard layout)"; }
}
