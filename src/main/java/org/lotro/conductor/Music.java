package org.lotro.conductor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** ABC catalog and deterministic cross-machine file-hash preflight. */
public final class Music {
    private Music() { }
    public record Part(int id, String title) {
        @Override public String toString() { return id + " — " + title; }
    }
    public record Song(Path file, String gamePath, String sha256, List<Part> parts) { }
    private static final Pattern X = Pattern.compile("^X:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern T = Pattern.compile("^T:\\s*(.*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALID_PATH = Pattern.compile("/[A-Za-z0-9_./ ()-]{1,250}");
    public static Song read(Path root, Path file) throws Exception {
        Path home = root.toAbsolutePath().normalize();
        Path song = file.toAbsolutePath().normalize();
        if (!song.startsWith(home) || !Files.isRegularFile(song) || !song.toString().toLowerCase(Locale.ROOT).endsWith(".abc"))
            throw new IOException("Choose an .abc file inside the configured Music folder");
        String gamePath = "/" + home.relativize(song).toString().replace('\\', '/').replaceAll("(?i)\\.abc$", "");
        validateGamePath(gamePath);
        List<Part> parts = new ArrayList<>();
        Integer current = null;
        String title = null;
        Set<Integer> ids = new HashSet<>();
        // Read with a BOM tolerant regex, matching SongBook3's scanner for standard ABC headers.
        String data = Files.readString(song, StandardCharsets.UTF_8).replace("\uFEFF", "");
        for (String line : data.split("\\R")) {
            Matcher x = X.matcher(line), t = T.matcher(line);
            if (x.find()) {
                if (current != null) { parts.add(new Part(current, title == null ? "(untitled)" : title)); }
                current = Integer.parseInt(x.group(1)); title = null;
                if (current <= 0 || current > 9999 || !ids.add(current)) throw new IOException("Invalid or duplicate X: track identifier");
            } else if (t.find()) {
                if (current == null) { current = 1; ids.add(1); }
                if (title == null) title = t.group(1).strip();
            }
        }
        if (current != null) parts.add(new Part(current, title == null ? "(untitled)" : title));
        if (parts.isEmpty()) throw new IOException("No X:/T: song tracks found");
        return new Song(song, gamePath, sha256(song), List.copyOf(parts));
    }
    public static void validateGamePath(String path) throws IOException {
        if (path == null || !VALID_PATH.matcher(path).matches() || path.contains("..") || path.endsWith("/"))
            throw new IOException("Unsafe/unsupported ABC path; use ASCII letters, digits, spaces, _, -, parentheses, . and / (no ..)");
    }
    public static void verifyLocalFile(Path root, String gamePath, String expectedHash) throws Exception {
        validateGamePath(gamePath);
        if (!expectedHash.matches("[0-9a-f]{64}")) throw new IOException("Invalid SHA-256 digest");
        Path base = root.toAbsolutePath().normalize();
        Path candidate = base.resolve(gamePath.substring(1) + ".abc").normalize();
        if (!candidate.startsWith(base) || !Files.isRegularFile(candidate))
            throw new IOException("ABC file missing: " + candidate);
        String hash = sha256(candidate);
        if (!hash.equals(expectedHash)) throw new IOException("ABC SHA-256 mismatch; update the music files on this computer");
    }
    public static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] block = new byte[16384]; int n;
            while ((n = input.read(block)) != -1) md.update(block, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
