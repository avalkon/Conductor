package org.lotro.conductor;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Remembers character details only. X11 IDs / Windows PIDs are deliberately NEVER persisted. */
public final class ProfileStore {
    public record Profile(String name, String instrument, Path bridgeStatus, Map<String,String> quickslots) {
        public Profile(String name, String instrument, Path bridgeStatus) { this(name,instrument,bridgeStatus,Map.of()); }
        /** Case-insensitive lookup: Planner/bridge labels can differ in capitalization. */
        public String shortcutFor(String instrument) {
            for(var mapping:quickslots.entrySet())
                if(mapping.getKey().equalsIgnoreCase(instrument))return mapping.getValue();
            return null;
        }
        public Profile {
            Objects.requireNonNull(name, "name");
            if (!name.matches("[\\p{L}0-9_-]{1,60}")) throw new IllegalArgumentException("Invalid character name");
            instrument = instrument == null ? "" : instrument.strip();
            if (instrument.length() > 150) throw new IllegalArgumentException("Instrument name too long");
            if (bridgeStatus != null) bridgeStatus = bridgeStatus.toAbsolutePath().normalize();
            Objects.requireNonNull(quickslots,"quickslots");
            Map<String,String> clean = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (quickslots.size() > 100) throw new IllegalArgumentException("Too many quickslot mappings");
            for (var entry : quickslots.entrySet()) {
                String label = Objects.requireNonNull(entry.getKey()).strip();
                if (label.isBlank() || label.length() > 150 || label.contains("\n") || label.contains("\r"))
                    throw new IllegalArgumentException("Invalid instrument label");
                clean.put(label, Shortcut.parse(entry.getValue()).toString());
            }
            quickslots = Map.copyOf(clean);
        }
    }
    private final Path path;
    private final Map<String,Profile> entries = new TreeMap<>();
    public ProfileStore(Path file) throws IOException {
        path = file.toAbsolutePath().normalize();
        if (Files.exists(path)) load();
    }
    public static Path defaultPath() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("windows")) {
            String appData = System.getenv("APPDATA");
            Path root = appData == null || appData.isBlank() ? Path.of(System.getProperty("user.home"), "AppData", "Roaming") : Path.of(appData);
            return root.resolve("LOTROConductor").resolve("profiles.properties");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path root = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute() ? Path.of(xdg) :
                Path.of(System.getProperty("user.home"), ".config");
        return root.resolve("lotro-conductor").resolve("profiles.properties");
    }
    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    public Path path() { return path; }
    public synchronized List<Profile> list() { return List.copyOf(entries.values()); }
    public synchronized Profile find(String name) { return entries.get(key(name)); }
    public synchronized void upsert(Profile profile) throws IOException {
        String key = key(profile.name());
        Profile old = entries.put(key, profile);
        try { save(); } catch (IOException e) {
            if (old == null) entries.remove(key); else entries.put(key, old);
            throw e;
        }
    }
    public synchronized void forget(String name) throws IOException {
        String k = key(name);
        Profile old = entries.remove(k);
        if (old == null) return;
        try { save(); } catch (IOException e) { entries.put(k, old); throw e; }
    }
    private void load() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) { properties.load(reader); }
        int n;
        try { n = Integer.parseInt(properties.getProperty("profiles.count", "0")); }
        catch (NumberFormatException e) { throw new IOException("Invalid profile count", e); }
        if (n < 0 || n > 100_000) throw new IOException("Invalid profile file size");
        for (int i = 0; i < n; i++) {
            String prefix = "profile." + i + ".";
            String name = properties.getProperty(prefix + "name", "");
            String instrument = properties.getProperty(prefix + "instrument", "");
            String file = properties.getProperty(prefix + "bridge", "");
            try {
                int slotCount = Integer.parseInt(properties.getProperty(prefix + "slots.count","0"));
                if (slotCount < 0 || slotCount > 100) throw new IllegalArgumentException("Invalid mapping count");
                Map<String,String> slots = new LinkedHashMap<>();
                for (int j=0; j<slotCount; j++)
                    slots.put(properties.getProperty(prefix+"slot."+j+".instrument",""),
                              properties.getProperty(prefix+"slot."+j+".key",""));
                Profile p = new Profile(name, instrument, file.isBlank() ? null : Path.of(file),slots);
                entries.put(key(name), p);
            } catch (IllegalArgumentException e) { throw new IOException("Invalid character profile at index " + i, e); }
        }
    }
    private void save() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("profiles.count", Integer.toString(entries.size()));
        int i = 0;
        for (Profile profile : entries.values()) {
            String prefix = "profile." + (i++) + ".";
            properties.setProperty(prefix + "name", profile.name());
            properties.setProperty(prefix + "instrument", profile.instrument());
            properties.setProperty(prefix + "bridge", profile.bridgeStatus() == null ? "" : profile.bridgeStatus().toString());
            properties.setProperty(prefix + "slots.count", Integer.toString(profile.quickslots().size()));
            int j = 0;
            for (var slot : profile.quickslots().entrySet()) {
                properties.setProperty(prefix+"slot."+j+".instrument",slot.getKey());
                properties.setProperty(prefix+"slot."+j+".key",slot.getValue());
                j++;
            }
        }
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "profiles-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "LOTRO Conductor saved character details (never stores X11 or Windows window identifiers)");
            }
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
