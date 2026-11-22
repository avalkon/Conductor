package org.lotro.conductor;

/** A character is uniquely owned by one connected computer. */
public record CharacterInfo(String nodeId, String computer, String name, String instrument,
                            String window, String state) {
    public String key() { return nodeId + "::" + name.toLowerCase(java.util.Locale.ROOT); }
}
