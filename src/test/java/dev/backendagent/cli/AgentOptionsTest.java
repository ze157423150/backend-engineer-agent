package dev.backendagent.cli;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentOptionsTest {
    @Test
    void requiresUserWorkspaceAndTaskInsteadOfSelectingFixedDefaults() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[0]));
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptions.parse(new String[] {"--workspace", "."}));
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptions.parse(new String[] {"--task", "inspect"}));
    }

    @Test
    void acceptsTaskWorkspaceConfigurationAndBudgetInAnyOrder() {
        var options = AgentOptions.parse(new String[] {"--task", "inspect cancellation", "--config", "local.properties",
                "--max-model-calls", "12", "--max-history-chars", "8000", "--workspace", "repo"});
        assertEquals(Path.of("repo"), options.getWorkspace());
        assertEquals("inspect cancellation", options.getTask());
        assertEquals(Path.of("local.properties"), options.getConfigFile());
        assertEquals(12, options.getMaxModelCalls());
        assertEquals(8000, options.getMaxHistoryCharacters());
    }

    @Test
    void rejectsDuplicateAndUnknownOptionsAndMissingValues() {
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                new String[] {"--workspace", ".", "--workspace", "repo", "--task", "inspect"}));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[] {"--deepseek"}));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                new String[] {"--workspace", ".", "--task"}));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                new String[] {"--workspace", "--task", "inspect"}));
    }

    @Test
    void validatesBudgetWithoutEchoingInvalidValue() {
        for (String value : new String[] {"0", "-1", "private-value"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(
                    new String[] {"--workspace", ".", "--task", "inspect", "--max-model-calls", value}));
            assertEquals("--max-model-calls must be a positive integer", failure.getMessage());
        }
        var options = AgentOptions.parse(new String[] {"--workspace", ".", "--task", "inspect"});
        assertEquals(20, options.getMaxModelCalls());
        assertEquals(64000, options.getMaxHistoryCharacters());
        assertNull(options.getConfigFile());
    }

    @Test
    void rejectsInvalidHistoryBudget() {
        for (String value : new String[] {"0", "-1", "invalid", "2147483648"}) {
            assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[] {
                    "--workspace", ".", "--task", "inspect", "--max-history-chars", value}));
        }
    }

    @Test
    void configuresSandboxWithoutLettingModelChooseImageOrTimeout() {
        var options = AgentOptions.parse(new String[] {"--workspace", ".", "--task", "test",
                "--sandbox-image", "custom:local", "--test-timeout-seconds", "60"});
        assertEquals("custom:local", options.getSandboxImage());
        assertEquals(60, options.getTestTimeoutSeconds());
        for (String timeout : new String[] {"0", "601", "invalid"}) {
            assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[] {
                    "--workspace", ".", "--task", "test", "--test-timeout-seconds", timeout}));
        }
    }

    @Test
    void queryRequiresNoWorkspaceTaskOrApiConfiguration() {
        String id = java.util.UUID.randomUUID().toString();
        var options = AgentOptions.parse(new String[] {"--show-session", id, "--data-dir", "saved"});
        assertEquals(id, options.getShowSession().toString());
        assertEquals(Path.of("saved"), options.getDataDirectory());
        assertNull(options.getWorkspace());
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[] {"--show-session", "../escape"}));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[] {
                "--show-session", id, "--task", "inspect"}));
        var run = AgentOptions.parse(new String[] {"--workspace", ".", "--task", "inspect"});
        assertEquals(Path.of(".agent-sessions"), run.getDataDirectory());
    }

    @Test
    void memoryImportRequiresNewTaskAndCannotBeMixedWithQuery() {
        String id = java.util.UUID.randomUUID().toString();
        var options = AgentOptions.parse(new String[] {"--workspace", ".", "--task", "new task", "--memory-from", id});
        assertEquals(id, options.getMemoryFrom().toString());
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[] {"--memory-from", id}));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[] {
                "--show-session", id, "--memory-from", id}));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[] {
                "--workspace", ".", "--task", "new", "--memory-from", "invalid"}));
    }
    @Test
    void exportOnlyAcceptsSessionNewOutputAndDataDirectory() {
        String id = java.util.UUID.randomUUID().toString();
        var options = AgentOptions.parse(new String[] {"--export-session", id, "--output-workspace", "review", "--data-dir", "sessions"});
        assertEquals(java.util.UUID.fromString(id), options.getExportSession());
        assertEquals(Path.of("review"), options.getOutputWorkspace());
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[] {"--export-session", id}));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[] {"--output-workspace", "review"}));
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[] {
                "--export-session", id, "--output-workspace", "review", "--task", "modify"}));
    }

    @Test
    void configuresMinimumSummaryInputAndRejectsInvalidValues() {
        assertEquals(2048, AgentOptions.parse(new String[]{"--workspace", ".", "--task", "inspect"}).getMinimumSummaryInputCharacters());
        assertEquals(4096, AgentOptions.parse(new String[]{"--workspace", ".", "--task", "inspect", "--min-summary-input-chars", "4096"}).getMinimumSummaryInputCharacters());
        for (String value : new String[]{"0", "-1", "invalid", "2147483648"}) {
            assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[]{"--workspace", ".", "--task", "inspect", "--min-summary-input-chars", value}));
        }
    }

}
