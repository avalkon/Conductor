package org.lotro.conductor;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.nio.file.Files;

/** Exactly one serialized input pipeline per physical computer. */
public final class LocalAgent implements AutoCloseable {
    public record Binding(String character, String instrument, GameWindow window, Path statusFile) { }
    private final Map<String, Binding> bindings = new ConcurrentHashMap<>();
    private final Map<String, BridgeStatus.Data> reports = new ConcurrentHashMap<>();
    /** Last successfully SENT instrument command, not a claim about actual game equipment.
     * Kept on the owning computer, never transmitted and never persisted. */
    private final LastEquipTracker equipHistory = new LastEquipTracker();

    private final ExecutorService inputQueue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "conductor-game-input"); t.setDaemon(true); return t;
    });
    // File-read monitoring is separated from the serialized game-input queue.
    private final ExecutorService reportQueue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "conductor-bridge-reports"); t.setDaemon(true); return t;
    });
    private final GameBackend backend;
    private final Path musicDir;
    private final boolean live;
    private final Consumer<String> logger;
    private BandClient network;
    private final ProfileStore profiles;
    private final BridgeMonitor monitor;
    public LocalAgent(GameBackend backend, Path musicDir, boolean live, Consumer<String> logger) {
        this(backend, musicDir, live, logger, null);
    }
    public LocalAgent(GameBackend backend, Path musicDir, boolean live, Consumer<String> logger, ProfileStore profiles) {
        this.backend = backend; this.musicDir = musicDir; this.live = live; this.logger = logger; this.profiles = profiles;
        try {
            monitor = new BridgeMonitor(file -> reportQueue.submit(() -> {
                for (Binding b : bindings.values()) if (file.equals(b.statusFile())) {
                    try { readBridge(b); }
                    catch (IOException e) { logger.accept("Bridge watcher for " + b.character() + ": " + e.getMessage()); }
                }
            }), logger);
        } catch (IOException e) { throw new java.io.UncheckedIOException("Cannot initialize bridge file watcher",e); }
    }
    public void setNetwork(BandClient network) { this.network = network; }
    public Collection<Binding> bindings() { return List.copyOf(bindings.values()); }
    public BridgeStatus.Data reportFor(String name) { return reports.get(name.toLowerCase(Locale.ROOT)); }
    /**
     * Explicit local challenge scan. Read saved character profiles first, send all
     * probes, then listen for delayed Lua PluginData writes concurrently. A report
     * alone never proves which running game window owns the character.
     */
    public List<String> identifyWindows(Path pluginDataRoot) throws Exception {
        return identifyWindows(pluginDataRoot, Duration.ofSeconds(120), logger, () -> false);
    }
    public List<String> identifyWindows(Path pluginDataRoot, Duration timeout,
                                        Consumer<String> progress, BooleanSupplier cancelled) throws Exception {
        if (!live) throw new IOException("Automatic identity probes require --live");
        if (timeout.isNegative() || timeout.isZero()) throw new IOException("Identification wait must be positive");
        // Reading existing profile paths avoids repeated full-tree scans.
        Map<Path, String> savedNames = new LinkedHashMap<>();
        Set<Path> conflictingPaths = new HashSet<>();
        if (profiles != null) for (ProfileStore.Profile profile : profiles.list()) {
            Path file = profile.bridgeStatus();
            if (file == null) continue;
            file = file.toAbsolutePath().normalize();
            if (conflictingPaths.contains(file)) continue;
            String prior = savedNames.putIfAbsent(file, profile.name());
            if (prior != null && !prior.equalsIgnoreCase(profile.name())) {
                progress.accept("Saved path belongs to multiple characters (ignored): " + file);
                savedNames.remove(file);
                conflictingPaths.add(file);
            }
        }
        LinkedHashSet<Path> knownFiles = new LinkedHashSet<>(savedNames.keySet());
        // Discover files only once initially, and occasionally for newly-created reports.
        knownFiles.addAll(BridgeDiscovery.statusFiles(pluginDataRoot));
        knownFiles.removeAll(conflictingPaths);
        if (knownFiles.isEmpty() && pluginDataRoot == null) {
            throw new IOException("No saved bridge paths; choose your PluginData folder or save a character profile.");
        }
        int existing = 0;
        for (Path file : knownFiles) {
            if (Files.isRegularFile(file)) {
                try {
                    BridgeStatus.Data data = BridgeStatus.read(file);
                    String expected = savedNames.get(file);
                    if (expected != null && !expected.equalsIgnoreCase(data.name()))
                        progress.accept("Saved profile mismatch: " + expected + " points to " + data.name() + " in " + file);
                    else existing++;
                } catch (IOException ignored) { /* may be written later */ }
            }
        }
        progress.accept("Using " + savedNames.size() + " saved bridge paths; " + existing +
                " readable existing reports. Existing reports identify characters, not game windows.");

        List<String> outcomes = new ArrayList<>();
        Map<String,GameWindow> pending = new LinkedHashMap<>();
        // Send the challenges up front: a 15+ second Lua save can then complete while
        // other clients are being probed, instead of timing out each window at 4s.
        for (GameWindow window : backend.listWindows()) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                outcomes.add("Identification cancelled before all windows were probed"); return outcomes;
            }
            try {
                if (bindings.values().stream().anyMatch(b -> b.window().id().equals(window.id()))) {
                    outcomes.add(window.id() + ": already registered; skipped"); continue;
                }
                if (!backend.isValidWindow(window)) {
                    outcomes.add(window.id() + ": no longer a verified LOTRO window; skipped"); continue;
                }
                String token = BridgeDiscovery.newToken();
                // An identity probe must use the SAME per-computer input queue as
                // remote commands and quickslots. Sending it from the UI worker
                // could otherwise steal focus halfway through another command.
                sendIdentificationChallenge(window, token);
                pending.put(token, window);
                progress.accept("Challenge input completed for " + window.id() +
                        " via " + backend.lastChatInputMethod() +
                        "; awaiting PluginData save (not game-acknowledged)");
            } catch (Exception error) {
                outcomes.add(window.id() + ": " + error.getMessage() + "; left unassigned");
            }
        }
        if (pending.isEmpty()) return List.copyOf(outcomes);
        long deadline = System.nanoTime() + timeout.toNanos();
        long nextRescan = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                outcomes.add("Identification cancelled; " + pending.size() + " window(s) remain unassigned");
                return List.copyOf(outcomes);
            }
            // Support new status files created for previously unseen characters.
            if (pluginDataRoot != null && System.nanoTime() >= nextRescan) {
                try {
                    knownFiles.addAll(BridgeDiscovery.statusFiles(pluginDataRoot));
                    knownFiles.removeAll(conflictingPaths);
                }
                catch (IOException e) { progress.accept("PluginData refresh: " + e.getMessage()); }
                nextRescan = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            }
            Map<String,List<BridgeDiscovery.Match>> received = new HashMap<>();
            for (BridgeDiscovery.Match candidate : BridgeDiscovery.reports(knownFiles)) {
                String nonce = candidate.report().token();
                if (pending.containsKey(nonce)) received.computeIfAbsent(nonce,k -> new ArrayList<>()).add(candidate);
            }
            Iterator<Map.Entry<String,GameWindow>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                GameWindow window = entry.getValue();
                List<BridgeDiscovery.Match> matches = received.getOrDefault(entry.getKey(), List.of());
                if (matches.isEmpty()) continue;
                iterator.remove();
                if (matches.size() != 1) {
                    outcomes.add(window.id() + ": challenge duplicated in multiple status files; left unassigned");
                    continue;
                }
                BridgeDiscovery.Match match = matches.get(0);
                BridgeStatus.Data report = match.report();
                String expected = savedNames.get(match.file());
                if (expected != null && !expected.equalsIgnoreCase(report.name())) {
                    outcomes.add(window.id() + ": profile mismatch for " + match.file() + "; left unassigned");
                    continue;
                }
                String key = report.name().toLowerCase(Locale.ROOT);
                if (bindings.containsKey(key)) {
                    outcomes.add(window.id() + ": " + report.name() + " already registered elsewhere; skipped");
                    continue;
                }
                try {
                    if (!backend.isValidWindow(window)) throw new IOException("game window no longer valid");
                    register(report.name(), report.instrument(), window, match.file());
                    applyBridge(bindings.get(key), report);
                    String result = window.id() + ": identified " + report.name() + " (" + match.file() + ")";
                    outcomes.add(result);
                    progress.accept(result);
                } catch (Exception error) {
                    outcomes.add(window.id() + ": " + error.getMessage() + "; left unassigned");
                }
            }
            if (!pending.isEmpty()) {
                try { TimeUnit.MILLISECONDS.sleep(350); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcomes.add("Identification interrupted; " + pending.size() + " window(s) remain unassigned");
                    return List.copyOf(outcomes);
                }
            }
        }
        for (GameWindow window : pending.values()) outcomes.add(window.id() +
                ": no identity response within " + timeout.toSeconds() + "s; left unassigned");
        return List.copyOf(outcomes);
    }
    /** Focus-only manual window identification shares the game-input queue,
     * preventing a user click from stealing focus during an active chat line.
     */
    public CompletableFuture<Void> focusCandidateWindow(GameWindow window) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        inputQueue.submit(() -> {
            try {
                if(backend.isSelectedLauncher(window))throw new IOException("Selected launcher is excluded from game windows");
                backend.focusWindow(window);
                completion.complete(null);
            } catch (Exception e) {
                completion.completeExceptionally(e);
            }
        });
        return completion;
    }

    /** Local-only recovery, including unregistered game clients. No key-down
     * events or chat commands are sent. It shares the same input queue. */
    public CompletableFuture<Void> resetCandidateKeyboard(GameWindow window) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        inputQueue.submit(() -> {
            try {
                if (!live || backend instanceof DemoBackend) throw new IOException("Keyboard reset requires live mode");
                if (!backend.isValidWindow(window)) throw new IOException("LOTRO window missing or changed; refresh windows");
                backend.resetKeyboard(window);
                completion.complete(null);
            } catch (Exception e) { completion.completeExceptionally(e); }
        });
        return completion;
    }

    /** Queue a host-window activation behind local input; no wire action. */
    public void afterLocalInput(Runnable task) { inputQueue.submit(task); }

    /** Serialize auto-identification behind ALL normal input on this computer.
     * Completion only means that the local input backend finished its settling
     * delay; it is not an acknowledgment from LOTRO or the Lua bridge.
     */
    private void sendIdentificationChallenge(GameWindow window, String token) throws Exception {
        Future<?> scheduled = inputQueue.submit(() -> {
            if (!backend.isValidWindow(window))
                throw new IOException("LOTRO window changed before identification input");
            backend.sendChat(window, "/conductor identify " + token);
            return null;
        });
        try {
            scheduled.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception failure) throw failure;
            throw e;
        } catch (InterruptedException e) {
            scheduled.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /** Block the designated launcher immediately, including an already
     * registered window or queued candidate. Do not delete character profiles
     * or unregister an existing character merely because selection changed. */
    public synchronized void setLauncherWindow(LauncherWindow launcher) {
        backend.setLauncherWindow(launcher);
        if(launcher==null)return;
        for(Binding binding:bindings.values())if(backend.isSelectedLauncher(binding.window()))
            logger.accept("Launcher excluded; game input blocked for "+binding.character()+" until a different launcher is selected or cleared");
    }
    public synchronized void register(String name, String instrument, GameWindow win, Path statusFile) throws IOException {
        String character = name.strip();
        if (!character.matches("[\\p{L}0-9_-]{1,60}")) throw new IOException("Character must be 1–60 letters, digits, _ or -");
        if (instrument.length() > 150) throw new IOException("Instrument name is too long");
        if (win == null) throw new IOException("Select a game window");
        if(backend.isSelectedLauncher(win))throw new IOException("This window is the selected launcher, not a game client");
        for (Binding existing : bindings.values()) {
            if (!existing.character().equalsIgnoreCase(character) && existing.window().id().equals(win.id()))
                throw new IOException("That game window is already registered to " + existing.character());
        }
        if (statusFile != null) statusFile = statusFile.toAbsolutePath().normalize();
        Binding binding = new Binding(character, instrument, win, statusFile);
        // Notify the coordinator before committing a local binding.
        network.send("REGISTER", character, instrument, win.title());
        Binding previous = bindings.put(character.toLowerCase(Locale.ROOT), binding);
        equipHistory.clear(character); // new window binding, so old input history is not trusted
        if (previous != null && !Objects.equals(previous.statusFile(), statusFile)) monitor.untrack(previous.statusFile());
        saveProfile(binding);
        monitor.track(statusFile);
        publishCapabilities(character);
    }
    private void saveProfile(Binding binding) {
        if (profiles == null) return;
        try {
            profiles.upsert(new ProfileStore.Profile(binding.character(), binding.instrument(), binding.statusFile(),
                    profiles.find(binding.character()) == null ? Map.of() : profiles.find(binding.character()).quickslots()));
        } catch (IOException e) { logger.accept("Could not save character profile: " + e.getMessage()); }
    }
    /** Share only the instrument names for which this computer has a usable
     * quickslot. The actual keystrokes are NEVER transmitted to the host. */
    public synchronized void publishCapabilities(String character) throws IOException {
        if(network == null) return;
        ProfileStore.Profile profile=profiles==null?null:profiles.find(character);
        network.send("CAPS_BEGIN",character);
        if(profile!=null) {
            List<String> names=new ArrayList<>(profile.quickslots().keySet());
            names.sort(String.CASE_INSENSITIVE_ORDER);
            for(String instrument:names) network.send("CAPS_ITEM",character,instrument);
        }
        network.send("CAPS_END",character);
    }
    /** A deliberately LOCAL test: never exposed as a remote ACTION protocol kind. */
    public void testQuickslot(String character, String instrument, Shortcut shortcut) {
        inputQueue.submit(() -> {
            try {
                Binding binding = bindings.get(character.toLowerCase(Locale.ROOT));
                if (binding == null) throw new IOException("Register " + character + " on this computer before testing a slot");
                if (!(backend instanceof DemoBackend) && !backend.isValidWindow(binding.window()))
                    throw new IOException("LOTRO window no longer valid; re-identify the character");
                if (live) {
                    backend.sendShortcut(binding.window(),shortcut);
                    equipHistory.recordSuccess(binding.character(),instrument,shortcut);
                }
                logger.accept((live ? "QUICKSLOT SENT" : "DRY-RUN QUICKSLOT")+ " " + binding.character() +
                        " " + instrument + " via " + shortcut + "; equip is NOT confirmed");
            } catch (Exception ex) { logger.accept("QUICKSLOT FAILED: " + ex.getMessage()); }
        });
    }
    /** Local-only setlist equip. For every assigned musician with a saved mapping,
     * send exactly ONE quickslot keypress; no report read, music toggle or confirmation wait.
     * Requests are serialized with other local input to avoid X11/Windows focus races.
     */
    public record EquipRequirement(String character, String instrument) { }
    public CompletableFuture<Void> equipAssignedInstruments(List<EquipRequirement> requirements,
                                                             BooleanSupplier stillCurrent) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (!live) {
            result.completeExceptionally(new IOException("Equip requires --live and a registered local game window"));
            return result;
        }
        List<EquipRequirement> request = List.copyOf(requirements);
        inputQueue.submit(() -> {
            List<String> problems = new ArrayList<>();
            Map<String,String> seen = new HashMap<>();
            try {
                // Skip invalid individual mappings but allow other musicians to equip.
                for (EquipRequirement wanted : request) {
                    String key = wanted.character().toLowerCase(Locale.ROOT);
                    String previous = seen.putIfAbsent(key,wanted.instrument());
                    if (previous != null) {
                        if (!previous.equalsIgnoreCase(wanted.instrument()))
                            problems.add("Conflicting setlist instruments for " + wanted.character() +
                                    ": " + previous + " and " + wanted.instrument());
                        continue; // exactly one quickslot activation per local musician
                    }
                    if (!stillCurrent.getAsBoolean()) {
                        problems.add("Setlist selection changed; cancelled remaining quickslots");
                        break;
                    }
                    try {
                        Binding binding = bindings.get(wanted.character().toLowerCase(Locale.ROOT));
                        if (binding == null) throw new IOException("Character not registered locally: " + wanted.character());
                        ProfileStore.Profile profile = profiles == null ? null : profiles.find(wanted.character());
                        String saved = profile == null ? null : profile.shortcutFor(wanted.instrument());
                        if (saved == null) throw new IOException("No quickslot mapped for " + wanted.character() + " / " + wanted.instrument());
                        Shortcut shortcut = Shortcut.parse(saved);
                        InstrumentSwitcher.equip(binding, wanted.instrument(), shortcut, backend, logger);
                        equipHistory.recordSuccess(binding.character(), wanted.instrument(), shortcut);
                    } catch (Exception e) {
                        problems.add(e.getMessage());
                        logger.accept("EQUIP SKIPPED: " + e.getMessage());
                    }
                }
                if (problems.isEmpty()) result.complete(null);
                else result.completeExceptionally(new IOException(String.join("; ", problems)));
            } catch (Exception e) { result.completeExceptionally(e); }
        });
        return result;
    }

    public void remove(String name) throws IOException {
        Binding previous = bindings.remove(name.toLowerCase(Locale.ROOT));
        equipHistory.clear(name);
        if (previous != null) { monitor.untrack(previous.statusFile()); reports.remove(previous.character().toLowerCase(Locale.ROOT)); network.send("REMOVE", previous.character()); }
    }
    public void onMessage(Wire.Message msg) {
        if (!msg.type().equals("DO")) return;
        inputQueue.submit(() -> {
            try {
                if (msg.fields().size() != 5 && msg.fields().size() != 6)
                    throw new IOException("Invalid agent action field count");
                String correlation = msg.fields().size() == 6 ? msg.at(5) : "";
                String name = msg.at(0), kind = msg.at(1), path = msg.at(2), track = msg.at(3), hash = msg.at(4);
                Binding binding = bindings.get(name.toLowerCase(Locale.ROOT));
                if (binding == null) throw new IOException("Character not registered on this computer");
                String outcome = perform(binding, kind, path, track, hash);
                if (correlation.isEmpty()) network.send("RESULT", binding.character(), outcome, kind);
                else network.send("RESULT", binding.character(), outcome, kind, correlation);
            } catch (Exception e) {
                String name = msg.fields().isEmpty() ? "?" : msg.at(0);
                logger.accept("FAILED for " + name + ": " + e.getMessage());
                try {
                    String kind = msg.fields().size() > 1 ? msg.at(1) : "UNKNOWN";
                    String correlation = msg.fields().size() == 6 ? msg.at(5) : "";
                    if (correlation.isEmpty()) network.send("RESULT", name, "FAILED: " + e.getMessage(), kind);
                    else network.send("RESULT", name, "FAILED: " + e.getMessage(), kind, correlation);
                }
                catch (IOException ignored) { }
            }
        });
    }
    private String perform(Binding b, String kind, String path, String track, String hash) throws Exception {
        if (!Set.of("REPORT", "MUSIC", "SELECT", "PREPARE", "START", "FOLLOW", "CUSTOM", "EQUIP", "EQUIP_CHANGED", "EQUIP_RESET", "SPACE", "FOCUS").contains(kind)) throw new IOException("Disallowed action");
        if (kind.equals("EQUIP_RESET")) {
            equipHistory.clear(b.character());
            logger.accept("EQUIP HISTORY CLEARED: " + b.character());
            return "EQUIP HISTORY CLEARED";
        }
        // Recheck bound window still exists, avoiding sending a command to a reused PID/XID.
        if (!(backend instanceof DemoBackend) && !backend.isValidWindow(b.window()))
            throw new IOException("Game window missing; click Refresh and re-register this character");
        if (kind.equals("EQUIP") || kind.equals("EQUIP_CHANGED")) {
            // The coordinator chooses an instrument; THIS machine alone resolves
            // that instrument into its private saved shortcut and current game window.
            if(path.isBlank() || path.length()>150)throw new IOException("Invalid equip instrument");
            ProfileStore.Profile profile=profiles==null?null:profiles.find(b.character());
            String saved=profile==null?null:profile.shortcutFor(path);
            if(saved==null) throw new IOException("No saved quickslot for " + b.character() + " / " + path);
            Shortcut shortcut=Shortcut.parse(saved);
            if(kind.equals("EQUIP_CHANGED") && equipHistory.isUnchanged(b.character(),path,shortcut)) {
                logger.accept("EQUIP SKIPPED (unchanged): " + b.character() + " => " + path);
                return "EQUIP SKIPPED: unchanged";
            }
            logger.accept((live?"EQUIP SENDING ":"DRY-RUN EQUIP ")+b.character()+" => "+path+" via "+shortcut);
            if(live) {
                InstrumentSwitcher.equip(b,path,shortcut,backend,logger);
                equipHistory.recordSuccess(b.character(),path,shortcut);
            }
            return live?"INPUT SENT: EQUIP":"DRY-RUN: EQUIP";
        }
        if (kind.equals("FOCUS")) {
            logger.accept((live ? "FOCUS REQUEST " : "DRY-RUN FOCUS ") + b.character());
            // Deliberately NO keyboard input. X11 verifies the requested active XID.
            // This is serialized behind prior local sync/input commands.
            if (live) backend.focusWindow(b.window());
            return live ? "INPUT SENT: FOCUS" : "DRY-RUN: FOCUS";
        }
        if (kind.equals("SPACE")) {
            logger.accept((live?"SPACE SENDING ":"DRY-RUN SPACE ")+b.character());
            if(live) backend.sendShortcut(b.window(),Shortcut.parse("SPACE"));
            return live?"INPUT SENT: SPACE":"DRY-RUN: SPACE";
        }
        if (kind.equals("FOLLOW")) {
            // ACTION field "path" is a bounded portable shortcut, NOT an arbitrary shell command.
            Shortcut selectNextFellow = Shortcut.parse(path);
            logger.accept((live ? "FOLLOW SENDING " : "DRY-RUN FOLLOW ") + b.character() +
                    " => " + selectNextFellow + " then /follow");
            if (live) {
                backend.sendShortcut(b.window(), selectNextFellow);
                Thread.sleep(180); // let LOTRO apply target selection before submitting /follow
                if (!backend.isValidWindow(b.window()))
                    throw new IOException("Window changed after selecting fellow; refusing /follow");
                backend.sendChat(b.window(), "/follow");
                logger.accept("CHAT INPUT SETTLED " + b.character() + " => FOLLOW [" +
                        backend.lastChatInputMethod() + "]");
            }
            return live ? "INPUT SENT: FOLLOW" : "DRY-RUN: FOLLOW";
        }
        List<String> commands = new ArrayList<>();
        switch (kind) {
            case "REPORT" -> commands.add("/conductor report");
            case "MUSIC" -> commands.add("/music");
            case "START" -> commands.add("/playstart");
            case "CUSTOM" -> commands.add(CommandValidator.requireCommand(path));
            case "SELECT", "PREPARE" -> {
                Music.verifyLocalFile(musicDir, path, hash);
                int part;
                try { part = Integer.parseInt(track); } catch (NumberFormatException e) { throw new IOException("Invalid track ID"); }
                if (part < 1 || part > 9999) throw new IOException("Invalid track ID");
                commands.add("/conductor select " + path + " | " + part);
                if (kind.equals("PREPARE")) commands.add("/play \"" + path + "\" " + part + " sync");
            }
        }
        for (String command : commands) {
            if (command.length() > 350) throw new IOException("Command line too long");
            logger.accept((live ? "SENDING " : "DRY-RUN ") + b.character() + " => " + command);
            if (live) {
                backend.sendChat(b.window(), command);
                // This marker (and the later RESULT sent to the host) occurs only
                // after the local backend's per-command post-submit settling barrier.
                // It does not claim that LOTRO accepted the command.
                logger.accept("CHAT INPUT SETTLED " + b.character() + " => " + kind +
                        " [" + backend.lastChatInputMethod() + "]");
            }
            if (commands.size() > 1 && command != commands.get(commands.size()-1)) Thread.sleep(270);
        }
        if (kind.equals("REPORT") && live && b.statusFile() != null) {
            logger.accept("Bridge report requested for " + b.character() +
                    "; watching " + b.statusFile() + " for LOTRO's delayed save");
        }
        return live ? "INPUT SENT: " + kind : "DRY-RUN: " + kind;
    }
    /** Explicitly refresh all local status files before running the suggestion algorithm.
     * Returns the latest verified instruments, without waiting for asynchronous roster updates.
     */
    public Map<String,String> refreshBridgeSnapshots() {
        Map<String,String> latest = new HashMap<>();
        for (Binding b : List.copyOf(bindings.values())) {
            if (b.statusFile() != null) {
                try { readBridge(b); }
                catch (IOException e) { logger.accept("Bridge refresh for " + b.character() + ": " + e.getMessage()); }
            }
            Binding current = bindings.get(b.character().toLowerCase(Locale.ROOT));
            if (current != null) latest.put(b.character().toLowerCase(Locale.ROOT), current.instrument());
        }
        return Map.copyOf(latest);
    }
    public String instrumentFor(String character) {
        Binding b = bindings.get(character.toLowerCase(Locale.ROOT));
        return b == null ? null : b.instrument();
    }
    public void requestReadBridge(String name) {
        inputQueue.submit(() -> {
            Binding b = bindings.get(name.toLowerCase(Locale.ROOT));
            if (b == null) { logger.accept("Character is not registered locally: " + name); return; }
            try { readBridge(b); }
            catch (Exception e) { logger.accept("Bridge status: " + e.getMessage()); }
        });
    }
    private void readBridge(Binding b) throws IOException {
        if (b.statusFile() == null) throw new IOException("No .plugindata status file selected for " + b.character());
        BridgeStatus.Data data = BridgeStatus.read(b.statusFile());
        applyBridge(b, data);
    }
    private void applyBridge(Binding b, BridgeStatus.Data data) throws IOException {
        if (!b.character().equalsIgnoreCase(data.name())) throw new IOException("Bridge file belongs to " + data.name() + ", not " + b.character());
        Binding updated = new Binding(b.character(), data.instrument(), b.window(), b.statusFile());
        String k = b.character().toLowerCase(Locale.ROOT);
        java.util.concurrent.atomic.AtomicBoolean applied = new java.util.concurrent.atomic.AtomicBoolean();
        bindings.computeIfPresent(k, (name, current) -> {
            // A newer instrument update on the same binding is fine; a rebound window is not.
            if (!current.window().equals(b.window()) || !Objects.equals(current.statusFile(), b.statusFile())) return current;
            applied.set(true);
            return new Binding(current.character(), data.instrument(), current.window(), current.statusFile());
        });
        if (!applied.get()) {
            logger.accept("Ignored bridge report from an older binding for " + b.character());
            return;
        }
        reports.put(k, data);
        saveProfile(updated);
        network.send("STATUS", b.character(), data.instrument(), "sync " + data.readiness());
        logger.accept("Bridge: " + b.character() + ", " + data.instrument() +
                ", sync=" + data.readiness());
    }
    @Override public void close() { monitor.close(); inputQueue.shutdownNow(); reportQueue.shutdownNow(); }
}
