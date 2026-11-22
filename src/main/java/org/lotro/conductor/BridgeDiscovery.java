package org.lotro.conductor;

import java.io.IOException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Looks for an explicit identification challenge in character-scoped PluginData files.
 * A saved report identifies a CHARACTER, not a running game WINDOW: the nonce connects them.
 */
public final class BridgeDiscovery {
    private BridgeDiscovery() { }
    private static final SecureRandom RANDOM = new SecureRandom();
    public record Match(Path file, BridgeStatus.Data report) { }

    public static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    /** One initial scan; do not traverse an entire PluginData tree on every 300ms poll. */
    public static List<Path> statusFiles(Path root) throws IOException {
        if (root == null) return List.of(); // Known saved profiles may be used without a root.
        if (!Files.isDirectory(root)) throw new IOException("Choose an existing LOTRO PluginData directory: " + root);
        try (var stream = Files.find(root, 7,
                (p, attrs) -> attrs.isRegularFile() && p.getFileName().toString().equalsIgnoreCase("ConductorStatus.plugindata"))) {
            return stream.limit(1000).map(p -> p.toAbsolutePath().normalize()).toList();
        }
    }
    /** Read each report ONCE per poll, even when many clients are pending. */
    public static List<Match> reports(Collection<Path> files) {
        List<Match> result = new ArrayList<>();
        for (Path p : files) {
            try { result.add(new Match(p.toAbsolutePath().normalize(), BridgeStatus.read(p))); }
            catch (IOException ignored) { /* save pending */ }
        }
        return List.copyOf(result);
    }
    /** Read the small known file set. A slow or partial Lua save is retried on the next poll. */
    public static List<Match> matchingFiles(Collection<Path> files, String token) throws IOException {
        if (!token.matches("[0-9a-f]{32}")) throw new IOException("Bad challenge token");
        return reports(files).stream().filter(m -> m.report().token().equals(token)).toList();
    }
    public static List<Match> matching(Path root, String token) throws IOException {
        return matchingFiles(statusFiles(root), token);
    }
    public static Optional<Match> awaitUnique(Path root, String token, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<Match> found = matching(root,token);
            if (found.size() > 1) throw new IOException("Identification token occurred in multiple status files; no window attached");
            if (found.size() == 1) return Optional.of(found.get(0));
            try { TimeUnit.MILLISECONDS.sleep(170); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Identification interrupted",ex); }
        }
        return Optional.empty();
    }
}
