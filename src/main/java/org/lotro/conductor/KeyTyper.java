package org.lotro.conductor;

import java.awt.*;
import java.awt.event.KeyEvent;

/** ASCII-only keyboard typing via AWT. Assumes US-layout key positions in this prototype. */
final class KeyTyper {
    private KeyTyper() { }
    static void command(String line) throws AWTException {
        Robot r = new Robot(); r.setAutoDelay(13);
        tap(r, KeyEvent.VK_ENTER);
        for (char c : line.toCharArray()) type(r, c);
        tap(r, KeyEvent.VK_ENTER);
    }
    private static void tap(Robot r, int code) { r.keyPress(code); r.keyRelease(code); }
    private static void type(Robot r, char c) {
        int code; boolean shift = false;
        if (c >= 'a' && c <= 'z') code = KeyEvent.VK_A + (c - 'a');
        else if (c >= 'A' && c <= 'Z') { code = KeyEvent.VK_A + (c - 'A'); shift = true; }
        else if (c >= '0' && c <= '9') code = KeyEvent.VK_0 + (c - '0');
        else switch (c) {
            case ' ' -> code = KeyEvent.VK_SPACE;
            case '/' -> code = KeyEvent.VK_SLASH;
            case '.' -> code = KeyEvent.VK_PERIOD;
            case '-' -> code = KeyEvent.VK_MINUS;
            case '_' -> { code = KeyEvent.VK_MINUS; shift = true; }
            case '\\' -> code = KeyEvent.VK_BACK_SLASH;
            case '"' -> { code = KeyEvent.VK_QUOTE; shift = true; }
            case '|' -> { code = KeyEvent.VK_BACK_SLASH; shift = true; }
            case '(' -> { code = KeyEvent.VK_9; shift = true; }
            case ')' -> { code = KeyEvent.VK_0; shift = true; }
            default -> throw new IllegalArgumentException("Unsupported Windows keyboard character: " + c);
        }
        if (shift) r.keyPress(KeyEvent.VK_SHIFT);
        tap(r, code);
        if (shift) r.keyRelease(KeyEvent.VK_SHIFT);
    }
}
