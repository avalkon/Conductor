package org.lotro.conductor;

import java.util.List;

/** Platform-specific window enumeration and targeted chat command dispatch. */
public interface GameBackend {
    List<GameWindow> listWindows() throws Exception;
    /** Local-only selection from Login; never inferred from a shared title. */
    default void setLauncherWindow(LauncherWindow launcher) { }
    default boolean isSelectedLauncher(GameWindow window) { return false; }
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
    /** Release only Ctrl/Shift/Alt in a verified local foreground game window. */
    default void resetKeyboard(GameWindow window) throws Exception {
        throw new UnsupportedOperationException("Keyboard reset is unavailable with this backend");
    }
    /** Local desktop input timing; agents never transmit this setting across the LAN. */
    default void setChatTiming(ChatInputTiming timing) { }
    /** Optional local clipboard input; leave off unless tested with this game client. */
    default void setChatPastePolicy(ChatPastePolicy policy) { }
    /** Last chat delivery path for diagnostic logging on this computer only. */
    default String lastChatInputMethod() { return "unspecified"; }
    void sendChat(GameWindow window, String command) throws Exception;
    String description();
}
