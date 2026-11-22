package org.lotro.conductor;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** WatchService notifications + a quiet six-second fallback for lost/missing events.
 * No busy polling: one background thread handles all watched directories.
 */
public final class BridgeMonitor implements AutoCloseable {
    private record Stamp(long modified, long size) { }
    private final WatchService watcher;
    private final Map<WatchKey,Path> directories = new HashMap<>();
    private final Map<Path,Stamp> seen = new HashMap<>();
    private final Consumer<Path> onChange;
    private final Consumer<String> log;
    private final Thread worker;
    private volatile boolean running = true;

    public BridgeMonitor(Consumer<Path> onChange, Consumer<String> log) throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.onChange = onChange; this.log = log;
        worker = new Thread(this::run, "conductor-bridge-watch");
        worker.setDaemon(true); worker.start();
    }
    public synchronized void track(Path file) {
        if (file == null) return;
        Path p = file.toAbsolutePath().normalize();
        seen.remove(p); // Handle a user changing/re-registering the status file.
        ensureParent(p);
        inspect(p);
    }
    public synchronized void untrack(Path file) {
        if (file != null) seen.remove(file.toAbsolutePath().normalize());
    }
    private void ensureParent(Path file) {
        Path dir = file.getParent();
        if (directories.containsValue(dir) || !Files.isDirectory(dir)) return;
        try {
            WatchKey key = dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            directories.put(key, dir);
        } catch (IOException e) { log.accept("Bridge watcher fallback for " + dir + ": " + e.getMessage()); }
    }
    private void inspect(Path path) {
        Stamp current = null;
        try {
            if (Files.isRegularFile(path)) {
                BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
                current = new Stamp(a.lastModifiedTime().to(TimeUnit.NANOSECONDS), a.size());
            }
        } catch (IOException e) { return; }
        Stamp previous = seen.put(path, current == null ? new Stamp(-1,-1) : current);
        if (current != null && !current.equals(previous)) onChange.accept(path);
    }
    private void run() {
        long fallbackAt = System.nanoTime();
        while (running) {
            try {
                WatchKey key = watcher.poll(800, TimeUnit.MILLISECONDS);
                if (key != null) {
                    Path dir;
                    Set<Path> affected = new HashSet<>();
                    synchronized (this) {
                        dir = directories.get(key);
                        if (dir != null) for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                                affected.addAll(seen.keySet());
                            } else if (event.context() instanceof Path filename) {
                                Path target = dir.resolve(filename).toAbsolutePath().normalize();
                                if (seen.containsKey(target)) affected.add(target);
                            }
                        }
                        if (!key.reset()) directories.remove(key);
                    }
                    // Turbine.PluginData.Save may write/replace a file in more than one step.
                    if (!affected.isEmpty()) Thread.sleep(180);
                    synchronized (this) { for (Path p : affected) inspect(p); }
                }
                if (System.nanoTime() >= fallbackAt) {
                    synchronized (this) {
                        for (Path p : List.copyOf(seen.keySet())) { ensureParent(p); inspect(p); }
                    }
                    fallbackAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
                }
            } catch (InterruptedException e) { if (!running) break; }
              catch (ClosedWatchServiceException e) { break; }
              catch (Exception e) { log.accept("Bridge watcher: " + e.getMessage()); }
        }
    }
    @Override public void close() {
        running = false;
        try { watcher.close(); } catch (IOException ignored) { }
        worker.interrupt();
    }
}
