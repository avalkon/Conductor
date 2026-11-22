package org.lotro.conductor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * X11-only, focus-and-raise operation for the Conductor FRAME (never a LOTRO client).
 *
 * Swing's JFrame.toFront() is only an activation request; some X11 window managers
 * focus a frame without stacking it above other windows. Find the unique top-level
 * window owned by THIS Java PID, verify its full title and PID, then explicitly raise
 * that XID. Never pick the first window with a vaguely matching title.
 */
final class ConductorWindowRaiser {
    @FunctionalInterface
    interface Runner {
        ProcessTools.Result run(int timeoutSeconds, String... args) throws Exception;
    }

    record Result(boolean raised, String description) { }

    private ConductorWindowRaiser() { }

    static Result raiseX11(String expectedTitle, long expectedPid) {
        return raiseX11(expectedTitle, expectedPid, ProcessTools::result);
    }

    /** Injection point enables checking exact target safety without a running desktop. */
    static Result raiseX11(String expectedTitle, long expectedPid, Runner process) {
        if (expectedTitle == null || expectedTitle.isBlank() || expectedPid <= 0)
            return new Result(false, "Conductor X11 window identity is unavailable");
        try {
            ProcessTools.Result listing = process.run(5, "wmctrl", "-l", "-p");
            if (listing.exitCode() != 0)
                return new Result(false, "X11 window enumeration failed: " + listing.output().strip());
            String id = selectUniqueWindow(listing.output(), expectedPid, expectedTitle);
            if (id == null)
                return new Result(false, "Could not uniquely identify the Conductor X11 window for PID " + expectedPid);
            String decimal = Long.toUnsignedString(Long.parseUnsignedLong(id.substring(2), 16));

            // Recheck the XID before changing focus: XIDs can be reused when windows close.
            String title = checked(process, "xdotool", "getwindowname", decimal).strip();
            String pidText = checked(process, "xdotool", "getwindowpid", decimal).strip();
            long actualPid;
            try { actualPid = Long.parseLong(pidText); }
            catch (NumberFormatException e) {
                return new Result(false, "Cannot verify Conductor X11 owner PID: " + pidText);
            }
            if (!Objects.equals(title, expectedTitle) || actualPid != expectedPid)
                return new Result(false, "Conductor X11 window identity changed; refusing to raise another window");

            // EWMH activation is not necessarily a raise. XRaiseWindow is the missing step.
            checked(process, "wmctrl", "-ia", id);
            checked(process, "xdotool", "windowraise", decimal);
            return new Result(true, "Requested X11 activation and raise for Conductor window " + id);
        } catch (Exception error) {
            return new Result(false, "Could not raise Conductor via X11: " + error.getMessage());
        }
    }

    private static String checked(Runner runner, String... command) throws Exception {
        ProcessTools.Result result = runner.run(5, command);
        if (result.exitCode() != 0)
            throw new IOException(command[0] + " exited " + result.exitCode() + ": " + result.output().strip());
        return result.output();
    }

    /** Return an XID only if its title AND owner PID match exactly, with no ambiguity. */
    static String selectUniqueWindow(String listing, long expectedPid, String title) {
        List<String> matches = new ArrayList<>();
        for (String line : listing.split("\\R")) {
            String[] fields = line.strip().split("\\s+", 5);
            if (fields.length != 5 || !fields[0].matches("0x[0-9a-fA-F]+")) continue;
            long pid;
            try { pid = Long.parseLong(fields[2]); }
            catch (NumberFormatException ignored) { continue; }
            if (pid == expectedPid && title.equals(fields[4])) matches.add(fields[0]);
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }
}
