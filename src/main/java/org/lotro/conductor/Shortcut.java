package org.lotro.conductor;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.*;

/** Portable, explicit keyboard shortcuts, including LOTRO's 12 keys per bar (1..9,0,-,=). */
public record Shortcut(boolean ctrl, boolean alt, boolean shift, String key) {
    public static final List<String> BAR_KEYS = List.of("1","2","3","4","5","6","7","8","9","0","-","=");

    public static Shortcut parse(String text) {
        if (text == null || text.isBlank() || text.length() > 50)
            throw new IllegalArgumentException("Enter 1, 0, -, =, Ctrl+2, Alt+=, Shift+- or F1-F12");
        boolean ctrl=false,alt=false,shift=false;
        String key=null;
        for (String part : text.strip().split("\\+", -1)) {
            String p=part.trim().toUpperCase(Locale.ROOT);
            switch(p) {
                case "CTRL", "CONTROL" -> { if (ctrl) throw new IllegalArgumentException("Duplicate Ctrl"); ctrl=true; }
                case "ALT" -> { if (alt) throw new IllegalArgumentException("Duplicate Alt"); alt=true; }
                case "SHIFT" -> { if (shift) throw new IllegalArgumentException("Duplicate Shift"); shift=true; }
                default -> {
                    if (key != null || !(p.matches("[A-Z0-9]") || p.equals("-") || p.equals("=")
                            || p.matches("F([1-9]|1[0-2])") || Set.of("TAB", "SPACE", "BACKSPACE").contains(p)))
                        throw new IllegalArgumentException("Allowed: A-Z, 0-9, -, =, F1-F12, Tab, Space, Backspace; optional Ctrl/Alt/Shift");
                    key=p;
                }
            }
        }
        if (key == null) throw new IllegalArgumentException("Missing shortcut key");
        return new Shortcut(ctrl,alt,shift,key);
    }

    /** The four LOTRO bars can be mapped without a modifier or with a single modifier. */
    public static Shortcut barSlot(String bar, String key) {
        if (!BAR_KEYS.contains(key)) throw new IllegalArgumentException("Not a LOTRO quickslot position");
        return switch (bar) {
            case "No modifier" -> new Shortcut(false,false,false,key);
            case "Ctrl" -> new Shortcut(true,false,false,key);
            case "Alt" -> new Shortcut(false,true,false,key);
            case "Shift" -> new Shortcut(false,false,true,key);
            default -> throw new IllegalArgumentException("Unknown quickslot bar: " + bar);
        };
    }

    /** Capture the physical key plus modifier state; never inserts the shortcut into the text field. */
    public static Shortcut capture(KeyEvent e) { return capture(e.getKeyCode(),e.getModifiersEx()); }
    public static Shortcut capture(int code,int modifiersEx) {
        String name;
        if(code>=KeyEvent.VK_0 && code<=KeyEvent.VK_9) name=Integer.toString(code-KeyEvent.VK_0);
        else if(code>=KeyEvent.VK_NUMPAD0 && code<=KeyEvent.VK_NUMPAD9) name=Integer.toString(code-KeyEvent.VK_NUMPAD0);
        else if(code>=KeyEvent.VK_A && code<=KeyEvent.VK_Z) name=Character.toString((char)('A'+code-KeyEvent.VK_A));
        else if(code>=KeyEvent.VK_F1 && code<=KeyEvent.VK_F12) name="F"+(code-KeyEvent.VK_F1+1);
        else if(code==KeyEvent.VK_MINUS || code==KeyEvent.VK_SUBTRACT) name="-";
        else if(code==KeyEvent.VK_EQUALS) name="=";
        else if(code==KeyEvent.VK_TAB) name="TAB";
        else if(code==KeyEvent.VK_SPACE) name="SPACE";
        else if(code==KeyEvent.VK_BACK_SPACE) name="BACKSPACE";
        else throw new IllegalArgumentException("Press a letter, number, -, = or F1–F12");
        return new Shortcut((modifiersEx&InputEvent.CTRL_DOWN_MASK)!=0,
                (modifiersEx&InputEvent.ALT_DOWN_MASK)!=0,
                (modifiersEx&InputEvent.SHIFT_DOWN_MASK)!=0,name);
    }
    public String x11() {
        String k = switch(key) {
            case "-" -> "minus"; case "=" -> "equal";
            case "TAB" -> "Tab"; case "SPACE" -> "space"; case "BACKSPACE" -> "BackSpace";
            default -> key.toLowerCase(Locale.ROOT);
        };
        return (ctrl?"ctrl+":"") + (alt?"alt+":"") + (shift?"shift+":"") + k;
    }
    public int keyCode() {
        if (key.equals("-")) return KeyEvent.VK_MINUS;
        if (key.equals("=")) return KeyEvent.VK_EQUALS;
        if (key.equals("TAB")) return KeyEvent.VK_TAB;
        if (key.equals("SPACE")) return KeyEvent.VK_SPACE;
        if (key.equals("BACKSPACE")) return KeyEvent.VK_BACK_SPACE;
        if (key.matches("F([1-9]|1[0-2])")) return KeyEvent.VK_F1 + Integer.parseInt(key.substring(1))-1;
        return KeyEvent.getExtendedKeyCodeForChar(key.charAt(0));
    }
    @Override public String toString() {
        return (ctrl?"Ctrl+":"")+(alt?"Alt+":"")+(shift?"Shift+":"")+key;
    }
}
