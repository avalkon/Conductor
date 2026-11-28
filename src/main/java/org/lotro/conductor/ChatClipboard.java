package org.lotro.conductor;

import java.awt.Toolkit;
import java.awt.datatransfer.*;
import java.io.Reader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One serialized clipboard lease. Never log clipboard contents. */
final class ChatClipboard {
    private ChatClipboard() { }
    private static final int ATTEMPTS = 3;
    static Clipboard desktopClipboard() { return Toolkit.getDefaultToolkit().getSystemClipboard(); }

    record Attempt(Lease lease, String reason) {
        String method() { return lease == null ? "typed (clipboard unavailable: " + reason + ")" : "paste"; }
    }

    static Attempt tryLease(Supplier<Clipboard> supplier, String command, boolean preserve)
            throws InterruptedException {
        for (int i = 0; i < ATTEMPTS; i++) {
            try { return acquireAttempt(supplier.get(), command, preserve); }
            catch (IllegalStateException busy) {
                if (i == ATTEMPTS - 1) return new Attempt(null,"clipboard busy after " + ATTEMPTS + " attempts");
                Thread.sleep(120);
            } catch (java.awt.HeadlessException e) {
                return new Attempt(null,"desktop clipboard unavailable (headless)");
            } catch (SecurityException e) {
                return new Attempt(null,"clipboard access denied");
            } catch (Exception | java.awt.AWTError | LinkageError e) {
                return new Attempt(null,"clipboard access failed (" + e.getClass().getSimpleName() + ")");
            }
        }
        throw new AssertionError("unreachable");
    }

    /** Compatibility helper for the in-memory clipboard tests. */
    static Lease acquire(Clipboard clipboard, String command) throws Exception {
        return acquireAttempt(clipboard, command, true).lease();
    }

    private static boolean plainText(DataFlavor f) {
        return f.equals(DataFlavor.stringFlavor) || f.isMimeTypeEqual("text/plain");
    }

    private static Attempt acquireAttempt(Clipboard clipboard, String command, boolean preserve) throws Exception {
        if (clipboard == null) return new Attempt(null,"no desktop clipboard");
        if (command == null || command.isEmpty()) return new Attempt(null,"empty command");
        String original = null;
        if (preserve) {
            Transferable previous = clipboard.getContents(null);
            // Some native desktops return a transferable with zero flavors for an
            // empty clipboard. AWT cannot portably restore 'no selection', so
            // restore empty text in this case.
            original = "";
            if (previous != null) {
                DataFlavor[] flavors = previous.getTransferDataFlavors();
                for (DataFlavor flavor : flavors) {
                    if (!plainText(flavor))
                        return new Attempt(null,"protected non-plain-text format (" +
                                flavor.getPrimaryType() + "/" + flavor.getSubType() + ")");
                }
                if (flavors.length > 0) {
                    if (previous.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        Object data = previous.getTransferData(DataFlavor.stringFlavor);
                        if (!(data instanceof String s)) return new Attempt(null,"plain-text format not readable");
                        original = s;
                    } else {
                        DataFlavor best = DataFlavor.selectBestTextFlavor(flavors);
                        if (best == null) return new Attempt(null,"plain-text format not readable");
                        try (Reader reader = best.getReaderForText(previous)) {
                            StringBuilder result = new StringBuilder();
                            char[] buffer = new char[4096];
                            int n;
                            while ((n = reader.read(buffer)) != -1) {
                                result.append(buffer, 0, n);
                                if (result.length() > 4_000_000)
                                    return new Attempt(null,"clipboard text too large to preserve");
                            }
                            original = result.toString();
                        }
                    }
                    if (original.length() > 4_000_000)
                        return new Attempt(null,"clipboard text too large to preserve");
                }
            }
        }
        AtomicBoolean owner = new AtomicBoolean(true);
        StringSelection injected = new StringSelection(command);
        clipboard.setContents(injected, (board, value) -> owner.set(false));
        return new Attempt(new Lease(clipboard, command, original, owner), "");
    }

    static final class Lease implements AutoCloseable {
        private final Clipboard clipboard;
        private final String command;
        /** Null means the user explicitly allowed the command to replace the clipboard. */
        private final String original;
        private final AtomicBoolean owns;
        private boolean closed;
        private String warning = "";
        private Lease(Clipboard clipboard, String command, String original, AtomicBoolean owns) {
            this.clipboard = clipboard;
            this.command = command;
            this.original = original;
            this.owns = owns;
        }
        String warning() { return warning; }
        private boolean stillCurrent() throws Exception {
            if (!owns.get()) return false;
            Transferable current = clipboard.getContents(null);
            return current != null && current.isDataFlavorSupported(DataFlavor.stringFlavor) &&
                    command.equals(current.getTransferData(DataFlavor.stringFlavor)) && owns.get();
        }
        /** Retest only transient clipboard locks; never blindly paste another app's copy. */
        boolean matchesCurrent() throws InterruptedException {
            for (int i = 0; i < ATTEMPTS; i++) {
                try { return stillCurrent(); }
                catch (IllegalStateException busy) {
                    if (i < ATTEMPTS - 1) Thread.sleep(120);
                } catch (Exception | java.awt.AWTError | LinkageError e) { return false; }
            }
            return false;
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (original == null) return;
            for (int i = 0; i < ATTEMPTS; i++) {
                try {
                    if (stillCurrent()) clipboard.setContents(new StringSelection(original), null);
                    return;
                } catch (IllegalStateException busy) {
                    if (i == ATTEMPTS - 1) warning = "clipboard restore busy";
                    else try { Thread.sleep(80); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); warning = "clipboard restore interrupted"; return;
                    }
                } catch (Exception | java.awt.AWTError | LinkageError e) {
                    warning = "clipboard restore failed (" + e.getClass().getSimpleName() + ")";
                    return;
                }
            }
        }
    }
}
