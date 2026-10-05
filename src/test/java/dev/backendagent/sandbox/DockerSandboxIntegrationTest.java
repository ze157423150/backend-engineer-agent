package dev.backendagent.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import dev.backendagent.tools.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real container verification after building the local image. Never invokes a model API. */
@EnabledIfSystemProperty(named = "docker.integration", matches = "true")
class DockerSandboxIntegrationTest {
    @TempDir Path root;

    @Test
    void realMavenFailsBeforePatchAndPassesAfterPatch() throws Exception {
        Path fixture = Path.of("examples/test-repair");
        try (var paths = Files.walk(fixture)) {
            for (Path source : paths.toList()) {
                Path target = root.resolve(fixture.relativize(source));
                if (Files.isDirectory(source)) { Files.createDirectories(target); }
                else { Files.copy(source, target); }
            }
        }
        var workspace = new Workspace(root, root.resolve("agent-local.properties"));
        var executor = new DockerSandboxExecutor(new LocalProcessRunner(), DockerSandboxExecutor.DEFAULT_IMAGE, Duration.ofSeconds(120));
        var failed = executor.runTests(workspace);
        assertFalse(failed.successful());
        assertTrue(failed.content().contains("exitCode=1"), failed.content());
        assertTrue(failed.content().contains("CalculatorTest"), failed.content());
        assertTrue(workspace.applyPatch("src/main/java/example/Calculator.java", "left - right", "left + right").successful());
        var passed = executor.runTests(workspace);
        assertTrue(passed.successful(), passed.content());
        assertTrue(passed.content().contains("BUILD SUCCESS"));
    }
}
