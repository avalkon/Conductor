package org.lotro.conductor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Captures stdout and stderr together, including partial output from failed X11 tools. */
final class ProcessTools {
    private ProcessTools() { }
    record Result(int exitCode, String output) { }

    static String run(int timeoutSeconds, String... args) throws Exception {
        Result result = result(timeoutSeconds, args);
        if (result.exitCode() != 0)
            throw new IOException(args[0] + " failed (" + result.exitCode() + "): " + result.output().strip());
        return result.output();
    }

    static Result result(int timeoutSeconds, String... args) throws Exception {
        Process proc = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream is = proc.getInputStream()) {
                byte[] chunk = new byte[4096]; int n;
                while ((n = is.read(chunk)) != -1) {
                    int remaining = 100_000 - output.size();
                    if (remaining > 0) output.write(chunk, 0, Math.min(n, remaining));
                }
            } catch (IOException ignored) { }
        }, "conductor-subprocess-output");
        reader.setDaemon(true);
        reader.start();
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IOException("Process timed out: " + args[0]);
        }
        reader.join(1000);
        return new Result(proc.exitValue(), output.toString(StandardCharsets.UTF_8));
    }
}
