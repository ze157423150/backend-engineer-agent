package dev.backendagent.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigLoaderTest {
    @TempDir
    Path directory;

    @Test
    void editorFileOverridesEnvironmentAndKeepsDefaultTimeouts() throws IOException {
        Path file = write("""
                model.api-key=local-test-key
                model.name=local-model
                model.base-url=https://example.com/v1
                """);
        ModelConfig config = AgentConfigLoader.load(file, Map.of(
                "DEEPSEEK_API_KEY", "env-test-key", "DEEPSEEK_MODEL", "env-model"));

        assertEquals("local-test-key", config.getApiKey());
        assertEquals("local-model", config.getModelName());
        assertEquals("https://example.com/v1", config.getBaseUrl().toString());
        assertEquals(Duration.ofSeconds(15), config.getConnectTimeout());
        assertEquals(Duration.ofSeconds(90), config.getRequestTimeout());
        assertFalse(config.toString().contains("local-test-key"));
    }

    @Test
    void blankFileKeyFallsBackToNamedEnvironmentVariable() throws IOException {
        Path file = write("""
                model.api-key=
                model.api-key-env=MY_MODEL_KEY
                model.connect-timeout-seconds=5
                model.request-timeout-seconds=30
                """);
        ModelConfig config = AgentConfigLoader.load(file, Map.of(
                "MY_MODEL_KEY", "custom-test-key", "DEEPSEEK_MODEL", "env-model"));

        assertEquals("custom-test-key", config.getApiKey());
        assertEquals("env-model", config.getModelName());
        assertEquals(Duration.ofSeconds(5), config.getConnectTimeout());
        assertEquals(Duration.ofSeconds(30), config.getRequestTimeout());
    }

    @Test
    void explicitMissingFileIsRejectedInsteadOfSilentlyUsingDefaults() {
        assertThrows(IllegalArgumentException.class, () -> AgentConfigLoader.load(
                directory.resolve("missing.properties"), Map.of("DEEPSEEK_API_KEY", "test-key")));
    }

    @Test
    void missingKeyProducesActionableMessage() throws IOException {
        Path file = write("model.api-key=\n");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AgentConfigLoader.load(file, Map.of()));
        assertTrue(failure.getMessage().contains("Fill model.api-key"));
    }

    @Test
    void invalidTimeoutDoesNotEchoConfigurationValue() throws IOException {
        Path file = write("model.api-key=test-key\nmodel.request-timeout-seconds=private-value\n");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AgentConfigLoader.load(file, Map.of()));
        assertTrue(failure.getMessage().contains("model.request-timeout-seconds"));
        assertFalse(failure.getMessage().contains("private-value"));
        assertFalse(failure.getMessage().contains("test-key"));
    }

    @Test
    void loadsLocalRequestBudgetAndAllowsZeroMargin() throws IOException {
        var file = write("model.api-key=test-key\nmodel.context-window-tokens=32000\n"
                + "model.max-output-tokens=3000\nmodel.safety-margin-tokens=0\n");
        var report = AgentConfigLoader.load(file, Map.of()).getRequestBudget().measure("x");
        assertEquals(32000, report.getContextWindowTokens());
        assertEquals(3000, report.getOutputTokens());
        assertEquals(0, report.getSafetyMarginTokens());
    }

    @Test
    void rejectsBudgetWithoutInputSpaceAndInvalidNumbersWithoutEchoingValues() throws IOException {
        var file = write("model.api-key=test-key\nmodel.context-window-tokens=3000\n"
                + "model.max-output-tokens=2000\nmodel.safety-margin-tokens=1000\n");
        Path noInputSpace = file;
        assertThrows(IllegalArgumentException.class, () -> AgentConfigLoader.load(noInputSpace, Map.of()));
        file = write("model.api-key=test-key\nmodel.max-output-tokens=private-value\n");
        Path invalid = file;
        var error = assertThrows(IllegalArgumentException.class, () -> AgentConfigLoader.load(invalid, Map.of()));
        assertFalse(error.getMessage().contains("private-value"));
        assertFalse(error.getMessage().contains("test-key"));
        assertTrue(error.getMessage().contains("model.max-output-tokens"));
    }

    private Path write(String content) throws IOException {
        Path file = directory.resolve("local.properties");
        Files.writeString(file, content);
        return file;
    }
}
