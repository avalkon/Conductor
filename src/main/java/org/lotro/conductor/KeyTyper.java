package org.lotro.conductor;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.datatransfer.Clipboard;
import java.util.function.Supplier;

/** Windows keyboard typing via AWT; US-layout physical key positions. */
final class KeyTyper {
    private KeyTyper() { }
    @FunctionalInterface interface FocusCheck { void verify() throws Exception; }
    interface Keys {
        void press(int code);
        void release(int code);
    }
    static Keys robotKeys(int delay) throws AWTException {
        Robot robot = new Robot();
        robot.setAutoDelay(delay);
        return new Keys() {
            public void press(int code) { robot.keyPress(code); }
            public void release(int code) { robot.keyRelease(code); }
        };
    }
    private static volatile String lastMethod = "unspecified";
    static String lastChatInputMethod() { return lastMethod; }

    static void command(String line, ChatInputTiming timing, ChatPastePolicy pastePolicy,
                        FocusCheck focus) throws Exception {
        command(line, timing, pastePolicy, focus, robotKeys(timing.keyDelayMs()), ChatClipboard::desktopClipboard);
    }

    static void command(String line, ChatInputTiming timing, ChatPastePolicy pastePolicy, FocusCheck focus,
                        Keys keys, Supplier<Clipboard> clipboard) throws Exception {
        ChatClipboard.Attempt attempt = pastePolicy.shouldPaste(line)
                ? ChatClipboard.tryLease(clipboard, line, pastePolicy.preserveClipboard()) : null;
        ChatClipboard.Lease lease = attempt == null ? null : attempt.lease();
        lastMethod = attempt == null ? "typed" : attempt.method();
        try {
            // Detect unsupported Windows layout characters before touching game chat.
            if (lease == null) for (char c : line.toCharArray()) stroke(c);
            focus.verify();
            tap(keys, KeyEvent.VK_ENTER);
            Thread.sleep(timing.chatOpenMs());
            if (lease != null) {
                if (!lease.matchesCurrent())
                    throw new IllegalStateException("Clipboard changed or became unreadable before Ctrl+V; chat not submitted");
                focus.verify();
                withPressed(keys, KeyEvent.VK_CONTROL, () -> tap(keys, KeyEvent.VK_V));
                Thread.sleep(Math.max(350, timing.beforeEnterMs()));
            } else {
                focus.verify();
                for (char c : line.toCharArray()) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    type(keys, c);
                }
                Thread.sleep(timing.beforeEnterMs());
            }
            focus.verify();
            tap(keys, KeyEvent.VK_ENTER);
            Thread.sleep(lease == null ? timing.afterEnterDelayMs(line) : Math.max(350, timing.afterEnterMs()));
        } finally {
            if (lease != null) {
                lease.close();
                if (!lease.warning().isEmpty()) lastMethod += " (" + lease.warning() + ")";
            }
        }
    }

    @FunctionalInterface private interface PressedAction { void run() throws InterruptedException; }
    /** Release even if press itself partially succeeds and throws. Nested
     * modifiers unwind in reverse order before the queue can switch windows. */
    private static void withPressed(Keys keys, int code, PressedAction action) throws InterruptedException {
        try {
            keys.press(code);
            action.run();
        } finally { keys.release(code); }
    }
    static void tap(Keys keys, int code) throws InterruptedException {
        withPressed(keys, code, () -> { });
    }
    static void shortcut(Keys keys, Shortcut shortcut) throws InterruptedException {
        PressedAction action = () -> withPressed(keys, shortcut.keyCode(), () -> Thread.sleep(65));
        if (shortcut.shift()) {
            PressedAction next = action;
            action = () -> withPressed(keys, KeyEvent.VK_SHIFT, next);
        }
        if (shortcut.alt()) {
            PressedAction next = action;
            action = () -> withPressed(keys, KeyEvent.VK_ALT, next);
        }
        if (shortcut.ctrl()) {
            PressedAction next = action;
            action = () -> withPressed(keys, KeyEvent.VK_CONTROL, next);
        }
        action.run();
    }

    private record Stroke(int code, boolean shift) { }
    private static Stroke stroke(char c) {
        int code; boolean shift = false;
        if (c >= 'a' && c <= 'z') code = KeyEvent.VK_A + (c - 'a');
        else if (c >= 'A' && c <= 'Z') { code = KeyEvent.VK_A + (c - 'A'); shift = true; }
        else if (c >= '0' && c <= '9') code = KeyEvent.VK_0 + (c - '0');
        else switch (c) {
            case ' ' -> code = KeyEvent.VK_SPACE;
            case '/' -> code = KeyEvent.VK_SLASH;
            case '.' -> code = KeyEvent.VK_PERIOD;
            case '-' -> code = KeyEvent.VK_MINUS;
            case '=' -> code = KeyEvent.VK_EQUALS;
            case '_' -> { code = KeyEvent.VK_MINUS; shift = true; }
            case '\\' -> code = KeyEvent.VK_BACK_SLASH;
            case '"' -> { code = KeyEvent.VK_QUOTE; shift = true; }
            case '\'' -> code = KeyEvent.VK_QUOTE;
            case '|' -> { code = KeyEvent.VK_BACK_SLASH; shift = true; }
            case '(' -> { code = KeyEvent.VK_9; shift = true; }
            case ')' -> { code = KeyEvent.VK_0; shift = true; }
            case ':' -> { code = KeyEvent.VK_SEMICOLON; shift = true; }
            case ';' -> code = KeyEvent.VK_SEMICOLON;
            case ',' -> code = KeyEvent.VK_COMMA;
            case '!' -> { code = KeyEvent.VK_1; shift = true; }
            case '?' -> { code = KeyEvent.VK_SLASH; shift = true; }
            case '@' -> { code = KeyEvent.VK_2; shift = true; }
            case '#' -> { code = KeyEvent.VK_3; shift = true; }
            case '$' -> { code = KeyEvent.VK_4; shift = true; }
            case '%' -> { code = KeyEvent.VK_5; shift = true; }
            case '^' -> { code = KeyEvent.VK_6; shift = true; }
            case '&' -> { code = KeyEvent.VK_7; shift = true; }
            case '*' -> { code = KeyEvent.VK_8; shift = true; }
            case '+' -> { code = KeyEvent.VK_EQUALS; shift = true; }
            case '[' -> code = KeyEvent.VK_OPEN_BRACKET;
            case ']' -> code = KeyEvent.VK_CLOSE_BRACKET;
            case '{' -> { code = KeyEvent.VK_OPEN_BRACKET; shift = true; }
            case '}' -> { code = KeyEvent.VK_CLOSE_BRACKET; shift = true; }
            case '<' -> { code = KeyEvent.VK_COMMA; shift = true; }
            case '>' -> { code = KeyEvent.VK_PERIOD; shift = true; }
            case '`' -> code = KeyEvent.VK_BACK_QUOTE;
            case '~' -> { code = KeyEvent.VK_BACK_QUOTE; shift = true; }
            default -> throw new IllegalArgumentException("Unsupported Windows keyboard character: " + c);
        }
        return new Stroke(code, shift);
    }
    static void type(Keys keys, char c) throws InterruptedException {
        Stroke stroke = stroke(c);
        if (stroke.shift()) withPressed(keys, KeyEvent.VK_SHIFT, () -> tap(keys, stroke.code()));
        else tap(keys, stroke.code());
    }
}
