package org.lotro.conductor;

public record GameWindow(String id, String title) {
    @Override public String toString() { return title + " [" + id + "]"; }
}
