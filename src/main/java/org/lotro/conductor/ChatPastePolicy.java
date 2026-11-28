package org.lotro.conductor;

/** Per-computer opt-in: paste long chat lines, otherwise use the existing typing backend. */
public record ChatPastePolicy(boolean enabled, int minimumCharacters, boolean preserveClipboard) {
    public static final ChatPastePolicy DEFAULT = new ChatPastePolicy(false, 40, true);

    public ChatPastePolicy(boolean enabled, int minimumCharacters) {
        this(enabled, minimumCharacters, true);
    }

    public ChatPastePolicy {
        if (minimumCharacters < 12 || minimumCharacters > 512)
            throw new IllegalArgumentException("Paste threshold must be 12–512 characters");
    }

    public boolean shouldPaste(String text) {
        return enabled && text != null && text.codePointCount(0, text.length()) >= minimumCharacters
                && text.indexOf('\n') < 0 && text.indexOf('\r') < 0;
    }
}
