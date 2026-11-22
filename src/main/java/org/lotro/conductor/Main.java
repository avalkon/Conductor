package org.lotro.conductor;

import javax.swing.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

public final class Main {
    public static void main(String[] args) {
        try {
            if (args.length == 1 && args[0].equals("--self-test")) {
                DevTests.main(new String[0]); return;
            }
            if (args.length == 1 && args[0].equals("--x11-probe")) {
                if (!System.getProperty("os.name").toLowerCase().contains("linux"))
                    throw new IllegalArgumentException("--x11-probe is for Linux X11 only");
                if (System.getenv("DISPLAY") == null)
                    throw new IllegalArgumentException("X11 DISPLAY is not set");
                var backend = new X11Backend();
                var windows = backend.listWindows();
                System.out.println("LOTRO candidate windows: " + windows.size());
                for (GameWindow window : windows) {
                    System.out.println("  " + window + " direct-check=" + backend.isValidWindow(window));
                }
                return;
            }
            Options options = Options.parse(args);
            if (!options.demo() && System.getProperty("os.name").toLowerCase().contains("linux") &&
                    System.getenv("DISPLAY") == null)
                throw new IllegalArgumentException("X11 DISPLAY is unset. Use --demo for headless testing or log in to X11.");
            AtomicReference<ConductorFrame> ref = new AtomicReference<>();
            GameBackend backend = options.demo()
                ? new DemoBackend(options.nodeName(), System.out::println)
                : System.getProperty("os.name").toLowerCase().contains("windows")
                    ? new WindowsBackend() : new X11Backend();
            BandServer server = options.host() ? new BandServer(options.port(), options.key(), options.local()) : null;
            if (server != null) server.start();
            ProfileStore profiles = new ProfileStore(options.profilesPath());
            LocalAgent agent = new LocalAgent(backend, options.musicDir(), options.live() && !options.demo(),
                    message -> { System.out.println(message); ConductorFrame frame = ref.get(); if (frame != null) frame.log(message); }, profiles);
            BandClient client;
            try {
                client = new BandClient(options.host() ? "127.0.0.1" : options.connectHost(), options.port(),
                        options.nodeName(), options.key(), options.host(), new BandClient.Listener() {
                    @Override public void received(Wire.Message m) {
                        if (m.type().equals("DO")) agent.onMessage(m);
                        ConductorFrame frame = ref.get(); if (frame != null) frame.onNetworkMessage(m);
                    }
                    @Override public void disconnected(String explanation) {
                        ConductorFrame frame = ref.get(); if (frame != null) frame.log("NETWORK DISCONNECTED: " + explanation);
                    }
                });
            } catch (Exception e) {
                agent.close(); if (server != null) server.close(); throw e;
            }
            agent.setNetwork(client);
            BandServer finalServer = server;
            SwingUtilities.invokeLater(() -> {
                ConductorFrame frame = new ConductorFrame(options, backend, agent, client, finalServer, profiles);
                ref.set(frame);
                frame.setVisible(true);
            });
        } catch (Exception e) {
            System.err.println("Conductor startup error: " + e.getMessage());
            System.err.println(Options.usage());
            System.exit(1);
        }
    }
}
