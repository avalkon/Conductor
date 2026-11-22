package org.lotro.conductor;

import java.util.*;

/** Correlates action-completion events with one user-triggered group. No game-state inference.
 * Called on Swing's event thread by the master UI. Tokens are unique to each action, so
 * out-of-order and duplicate network events cannot complete a group prematurely. */
final class CommandFocusTracker {
    static final class Batch {
        private final Set<String> outstanding = new HashSet<>();
        private final Runnable finished;
        private boolean done;
        Batch(Runnable finished) { this.finished = finished; }
        int pending() { return outstanding.size(); }
    }
    private final Map<String, Batch> pendingByToken = new HashMap<>();

    static boolean validToken(String value) {
        return value != null && value.matches("[a-f0-9-]{36}");
    }
    List<String> begin(int count, Runnable finished) {
        if (count < 1) throw new IllegalArgumentException("Expected at least one action");
        Batch batch = new Batch(Objects.requireNonNull(finished));
        List<String> tokens = new ArrayList<>(count);
        for (int n = 0; n < count; n++) {
            String token = UUID.randomUUID().toString();
            batch.outstanding.add(token);
            pendingByToken.put(token, batch);
            tokens.add(token);
        }
        return List.copyOf(tokens);
    }
    boolean complete(String token) {
        Batch batch = pendingByToken.remove(token);
        if (batch == null || batch.done) return false;
        batch.outstanding.remove(token);
        if (batch.outstanding.isEmpty()) finish(batch);
        return true;
    }
    int pending(String token) {
        Batch batch = pendingByToken.get(token);
        return batch == null ? 0 : batch.pending();
    }
    int expire(String token) {
        Batch batch = pendingByToken.get(token);
        if (batch == null || batch.done) return 0;
        int unconfirmed = batch.pending();
        finish(batch);
        return unconfirmed;
    }
    void clear() {
        for (Batch batch : new HashSet<>(pendingByToken.values())) batch.done = true;
        pendingByToken.clear();
    }
    private void finish(Batch batch) {
        if (batch.done) return;
        batch.done = true;
        for (String token : batch.outstanding) pendingByToken.remove(token);
        batch.outstanding.clear();
        batch.finished.run();
    }
}
