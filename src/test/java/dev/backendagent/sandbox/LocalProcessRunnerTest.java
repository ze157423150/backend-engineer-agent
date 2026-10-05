package dev.backendagent.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalProcessRunnerTest {
    @Test
    void capturesMergedOutputAndExitCodeWithoutShellExpansion() throws Exception {
        var result = new LocalProcessRunner().run(command("output", "$(echo wrong);literal"), Duration.ofSeconds(5));
        assertEquals(7, result.getExitCode());
        assertFalse(result.isTimedOut());
        assertTrue(result.getOutput().contains("stdout $(echo wrong);literal"));
        assertTrue(result.getOutput().contains("stderr"));
    }

    @Test
    void drainsLargeOutputAndRetainsBeginningAndEnd() throws Exception {
        var result = new LocalProcessRunner().run(command("flood"), Duration.ofSeconds(5));
        assertEquals(0, result.getExitCode());
        assertTrue(result.getOutput().startsWith("BEGIN"));
        assertTrue(result.getOutput().endsWith("END\n"));
        assertTrue(result.getOutput().contains("日志已截断"));
        assertTrue(result.getOutput().length() < 17000);
    }

    @Test
    void terminatesLongRunningProcessAtDeadline() throws Exception {
        long start = System.nanoTime();
        var result = new LocalProcessRunner().run(command("sleep"), Duration.ofMillis(200));
        assertTrue(result.isTimedOut());
        assertEquals(-1, result.getExitCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
    }

    private List<String> command(String... args) {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ProcessFixture.class.getName()));
        command.addAll(List.of(args));
        return command;
    }
}
