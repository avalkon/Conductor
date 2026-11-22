package org.lotro.conductor;

import java.nio.file.Path;

/** Immutable settings parsed from terminal flags. No changes to OS or LAN configuration. */
public record Options(boolean host, String connectHost, String nodeName, String key,
                      int port, Path musicDir, boolean live, boolean demo, boolean local, Path profilesPath) {
    public static Options parse(String[] args) {
        boolean host = false, join = false, live = false, demo = false, local = false;
        String ip = "", name = System.getenv().getOrDefault("COMPUTERNAME",
                System.getenv().getOrDefault("HOSTNAME", "Computer"));
        String key = "";
        int port = 38791;
        Path profilesPath = ProfileStore.defaultPath();
        Path musicDir = Path.of(System.getProperty("user.home"), "Documents", "The Lord of the Rings Online", "Music");
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--host" -> host = true;
                case "--local" -> local = true;
                case "--join" -> { join = true; ip = value(args, ++i, a); }
                case "--name" -> name = value(args, ++i, a);
                case "--key" -> key = value(args, ++i, a);
                case "--port" -> port = Integer.parseInt(value(args, ++i, a));
                case "--music-dir" -> musicDir = Path.of(value(args, ++i, a)).toAbsolutePath().normalize();
                case "--profiles" -> profilesPath = Path.of(value(args, ++i, a)).toAbsolutePath().normalize();
                case "--live" -> live = true;
                case "--demo" -> demo = true;
                case "--help", "-h" -> throw new IllegalArgumentException(usage());
                default -> throw new IllegalArgumentException("Unknown option: " + a + "\n" + usage());
            }
        }
        if (local) {
            if (host || join) throw new IllegalArgumentException("--local cannot be combined with --host or --join");
            host = true;
            // This one-machine mode binds only 127.0.0.1; no shared password to type.
            if (key.isBlank()) key = java.util.UUID.randomUUID().toString();
        }
        if (host == join) throw new IllegalArgumentException("Specify --local OR exactly one of --host / --join HOST\n" + usage());
        if (key.length() < 8) throw new IllegalArgumentException("--key must contain at least 8 characters (development LAN shared secret)");
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("--port must be 1024..65535");
        if (name.isBlank() || name.length() > 60) throw new IllegalArgumentException("--name must be 1..60 characters");
        if (ip.contains(":") || ip.contains("/")) throw new IllegalArgumentException("--join requires hostname or IPv4 only; use --port separately");
        return new Options(host, ip, name, key, port, musicDir, live, demo, local, profilesPath);
    }
    private static String value(String[] a, int i, String flag) {
        if (i >= a.length) throw new IllegalArgumentException("Missing value for " + flag);
        return a[i];
    }
    public static String usage() {
        return "Usage: java -jar conductor.jar --local OR --host|--join HOST --key SECRET " +
                "[--port 38791] [--music-dir PATH] [--profiles FILE] [--demo] [--live]\n" +
                "Diagnostic: --x11-probe lists LOTRO XIDs without input. Default is SAFE DRY-RUN: --live is required to type into LOTRO windows.";
    }
}
