package org.lotro.conductor;

import java.util.*;
import java.util.function.Consumer;

/** Explicitly safe mock driver for integration testing without any game. */
public final class DemoBackend implements GameBackend {
    private final String computer;
    private final Consumer<String> output;
    public DemoBackend(String computer, Consumer<String> output) { this.computer = computer; this.output = output; }
    @Override public List<GameWindow> listWindows() {
        return List.of(new GameWindow("demo-1", computer + " Fake LOTRO #1"),
                       new GameWindow("demo-2", computer + " Fake LOTRO #2"));
    }
    @Override public void focusWindow(GameWindow window) {
        output.accept("DEMO " + window + " <= focus-only");
    }
    @Override public void sendChat(GameWindow window, String command) { output.accept("DEMO " + window + " <= " + command); }
    @Override public void sendShortcut(GameWindow window, Shortcut shortcut) {
        output.accept("DEMO " + window + " <= quickslot " + shortcut);
    }
    @Override public String description() { return "Mock windows: no real keyboard input"; }
}
