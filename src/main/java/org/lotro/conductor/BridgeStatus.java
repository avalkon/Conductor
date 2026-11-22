package org.lotro.conductor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.regex.*;

/** Character-scoped Turbine PluginData reports; backwards compatible with CG1. */
public final class BridgeStatus {
    private BridgeStatus() { }
    private static final Pattern CG1 = Pattern.compile("CG1%([^%\\r\\n]{1,100})%([^%\\r\\n]{0,150})%([^%\\r\\n]{0,280})%([0-9]{1,5})%");
    private static final Pattern CG2 = Pattern.compile("CG2%([^%\\r\\n]{1,100})%([^%\\r\\n]{0,150})%([^%\\r\\n]{0,280})%([0-9]{1,5})%(unknown|observed-on|observed-off)%(ready|unconfirmed)%([0-9a-f]{0,64})%");
    public record Data(String name, String instrument, String song, int track,
                       String musicMode, String readiness, String token) {
        public Data(String name, String instrument, String song, int track) {
            this(name, instrument, song, track, "unknown", "unconfirmed", "");
        }
        public boolean hasModeEvidence() { return !musicMode.equals("unknown"); }
        public boolean ready() { return readiness.equals("ready"); }
    }
    public static FileTime modified(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.getLastModifiedTime(file) : null;
    }
    public static Data waitForNewReport(Path file, FileTime before, Duration timeout) throws IOException {
        long end = System.nanoTime() + timeout.toNanos();
        IOException parseFailure = null;
        while (System.nanoTime() < end) {
            FileTime now = modified(file);
            if (now != null && !now.equals(before)) {
                try { return read(file); }
                catch (IOException error) { parseFailure = error; }
            }
            try { Thread.sleep(120); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted while reading bridge report", e); }
        }
        if (parseFailure != null) throw parseFailure;
        throw new IOException("Bridge status was not updated after /conductor report");
    }
    public static Data read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("No bridge status file: " + file);
        if (Files.size(file) > 100_000) throw new IOException("Status file too large");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Matcher m = CG2.matcher(text);
        if (m.find()) return new Data(m.group(1),m.group(2),m.group(3),Integer.parseInt(m.group(4)),
                m.group(5),m.group(6),m.group(7));
        m = CG1.matcher(text);
        if (m.find()) return new Data(m.group(1),m.group(2),m.group(3),Integer.parseInt(m.group(4)));
        throw new IOException("No CG1/CG2 report; run /conductor report in game");
    }
}
