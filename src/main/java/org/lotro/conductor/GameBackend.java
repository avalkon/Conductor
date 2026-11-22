package org.lotro.conductor;

import java.util.List;

/** Platform-specific window enumeration and targeted chat command dispatch. */
public interface GameBackend {
    List<GameWindow> listWindows() throws Exception;
    default boolean isValidWindow(GameWindow window) throws Exception {
        return listWindows().stream().anyMatch(w -> w.id().equals(window.id()) && w.title().equals(window.title()));
    }
    /** Bring selected local game window to front but never type. Useful for mapping identical titles. */
    default void focusWindow(GameWindow window) throws Exception {
        throw new UnsupportedOperationException("Focus-only identification unavailable with this backend");
    }
    /** Targeted quickslot key event (not a chat command); never infer a default binding. */
    default void sendShortcut(GameWindow window, Shortcut shortcut) throws Exception {
        throw new UnsupportedOperationException("Quickslot input is unsupported by this backend");
    }
    void sendChat(GameWindow window, String command) throws Exception;
    String description();
}
