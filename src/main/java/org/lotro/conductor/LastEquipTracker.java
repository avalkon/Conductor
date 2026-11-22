package org.lotro.conductor;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Per-character history of successful conductor key dispatch, never inferred game state.
 * Recording happens only after GameBackend.sendShortcut returns successfully. */
final class LastEquipTracker {
    private record Sent(String instrument, Shortcut shortcut) { }
    private final ConcurrentHashMap<String, Sent> last = new ConcurrentHashMap<>();

    boolean isUnchanged(String character, String instrument, Shortcut shortcut) {
        Sent sent = last.get(key(character));
        return sent != null && sent.instrument().equalsIgnoreCase(instrument.strip())
                && sent.shortcut().equals(shortcut);
    }
    void recordSuccess(String character, String instrument, Shortcut shortcut) {
        last.put(key(character), new Sent(instrument.strip(), shortcut));
    }
    void clear(String character) {last.remove(key(character));}
    private static String key(String character) {return character.strip().toLowerCase(Locale.ROOT);}
}
