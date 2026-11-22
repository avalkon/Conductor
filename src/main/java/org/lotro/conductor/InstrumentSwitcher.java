package org.lotro.conductor;

import java.io.IOException;
import java.util.function.Consumer;

/** A quickslot equip is a single targeted keystroke; no bridge or music-mode inference. */
public final class InstrumentSwitcher {
    private InstrumentSwitcher() { }

    public static void equip(LocalAgent.Binding binding, String instrument, Shortcut shortcut,
                             GameBackend backend, Consumer<String> log) throws Exception {
        if (binding == null || shortcut == null) throw new IOException("Missing window or quickslot mapping");
        if (!(backend instanceof DemoBackend) && !backend.isValidWindow(binding.window()))
            throw new IOException("LOTRO window no longer verified for " + binding.character());
        backend.sendShortcut(binding.window(), shortcut);
        log.accept("EQUIP SENT: " + binding.character() + " => " + instrument + " via " + shortcut);
    }
}
