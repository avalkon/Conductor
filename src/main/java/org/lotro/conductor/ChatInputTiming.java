package org.lotro.conductor;

/**
 * Local per-computer chat pacing. The post-Enter wait is a delivery barrier,
 * not proof LOTRO consumed the command.
 */
public record ChatInputTiming(int chatOpenMs, int keyDelayMs, int beforeEnterMs,
                              int afterEnterMs, int afterEnterPerCharacterMs) {
    /** Preserve v0.12.6 behavior unless the musician enables length-based settling. */
    public static final ChatInputTiming DEFAULT = new ChatInputTiming(300, 45, 220, 550, 0);
    public static final int MAX_SETTLE_MS = 60_000;

    /** Source compatibility for existing code and settings with four delays. */
    public ChatInputTiming(int chatOpenMs, int keyDelayMs, int beforeEnterMs, int afterEnterMs) {
        this(chatOpenMs, keyDelayMs, beforeEnterMs, afterEnterMs, 0);
    }

    public ChatInputTiming {
        if (chatOpenMs < 0 || chatOpenMs > 5000 || keyDelayMs < 0 || keyDelayMs > 200 ||
                beforeEnterMs < 0 || beforeEnterMs > 5000 || afterEnterMs < 0 || afterEnterMs > 5000 ||
                afterEnterPerCharacterMs < 0 || afterEnterPerCharacterMs > 100)
            throw new IllegalArgumentException("Chat timing outside allowed limits");
    }

    /**
     * Delay applied after Enter, before another LOTRO window may receive focus.
     * Count actual Unicode characters (including punctuation/spaces), not the X11
     * command-line arguments or byte length. A cap prevents very long text from
     * holding the input queue indefinitely.
     */
    public int afterEnterDelayMs(String typedText) {
        if (typedText == null) throw new IllegalArgumentException("Typed chat text cannot be null");
        int characters = typedText.codePointCount(0, typedText.length());
        long calculated = (long) afterEnterMs + (long) characters * afterEnterPerCharacterMs;
        return (int) Math.min(calculated, MAX_SETTLE_MS);
    }

    /** Long ABC paths at a slow key pace must not time out mid-command. */
    public int typeTimeoutSeconds(int length) {
        return Math.max(10, (int) Math.ceil((long) length * Math.max(1, keyDelayMs) / 1000.0) + 10);
    }
}
