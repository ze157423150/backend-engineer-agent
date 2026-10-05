package dev.backendagent.sandbox;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs argument lists without a shell. Continuously drains merged output while bounding retained logs. */
public final class LocalProcessRunner implements CommandRunner {
    @Override
    public CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        var logs = new LogBuffer();
        Thread reader = Thread.ofPlatform().daemon(true).start(() -> {
            try (var input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] chunk = new char[2048];
                int read;
                while ((read = input.read(chunk)) != -1) { logs.append(new String(chunk, 0, read)); }
            } catch (IOException failure) { logs.append("\n[output stream closed]\n"); }
        });
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) { terminate(process); }
            reader.join(2000);
            if (reader.isAlive()) {
                process.getInputStream().close();
                reader.join(1000);
            }
            return new CommandResult(finished ? process.exitValue() : -1, !finished, logs.text());
        } finally {
            if (process.isAlive()) { terminate(process); }
            process.getInputStream().close();
        }
    }

    private void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static final class LogBuffer {
        private final StringBuilder head = new StringBuilder();
        private final StringBuilder tail = new StringBuilder();
        private long characters;

        synchronized void append(String text) {
            characters += text.length();
            int first = Math.min(8000 - head.length(), text.length());
            head.append(text, 0, first);
            tail.append(text.substring(first));
            if (tail.length() > 8000) { tail.delete(0, tail.length() - 8000); }
        }
        synchronized String text() {
            return head + (characters > 16000 ? "\n[中间日志已截断，保留开头及结尾]\n" : "") + tail;
        }
    }
}
