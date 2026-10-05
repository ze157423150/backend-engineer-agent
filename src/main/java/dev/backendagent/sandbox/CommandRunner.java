package dev.backendagent.sandbox;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** Process boundary used to test Docker orchestration without requiring a daemon. */
public interface CommandRunner {
    CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException;
}
