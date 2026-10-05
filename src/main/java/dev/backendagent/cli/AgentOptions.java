package dev.backendagent.cli;

import java.nio.file.Path;
import java.util.HashSet;

/** User-supplied task and workspace, separate from model credentials. */
public final class AgentOptions {
    public static final String USAGE = """
            Usage: java -jar <jar> --workspace <directory> --task <objective>
                   [--config <properties-file>] [--max-model-calls <positive-integer>]
                   [--max-history-chars <positive-integer>]
                   [--sandbox-image <local-image>] [--test-timeout-seconds <1-600>]
                   [--data-dir <directory>] [--memory-from <uuid>]
                   java -jar <jar> --resume-session <uuid> --workspace <directory>
                   [--max-model-calls <total-limit>] [--config <properties-file>] [--data-dir <directory>]
                   java -jar <jar> --show-session <uuid> [--data-dir <directory>]
                   java -jar <jar> --help
            """;

    private final Path workspace;
    private final String task;
    private final Path configFile;
    private final int maxModelCalls;
    private final int maxHistoryCharacters;
    private final String sandboxImage;
    private final int testTimeoutSeconds;
    private final Path dataDirectory;
    private final java.util.UUID showSession;
    private final java.util.UUID memoryFrom;
    private final java.util.UUID resumeSession;

    private AgentOptions(Path workspace, String task, Path configFile, int maxModelCalls, int maxHistoryCharacters,
                         String sandboxImage, int testTimeoutSeconds, Path dataDirectory, java.util.UUID showSession,
                         java.util.UUID memoryFrom, java.util.UUID resumeSession) {
        this.workspace = workspace;
        this.task = task;
        this.configFile = configFile;
        this.maxModelCalls = maxModelCalls;
        this.maxHistoryCharacters = maxHistoryCharacters;
        this.sandboxImage = sandboxImage;
        this.testTimeoutSeconds = testTimeoutSeconds;
        this.dataDirectory = dataDirectory;
        this.showSession = showSession;
        this.memoryFrom = memoryFrom;
        this.resumeSession = resumeSession;
    }

    public static AgentOptions parse(String[] args) {
        Path workspace = null;
        String task = null;
        Path configFile = null;
        int maxModelCalls = 20;
        int maxHistoryCharacters = dev.backendagent.runtime.ContextBudget.DEFAULT_MAX_HISTORY_CHARACTERS;
        String sandboxImage = dev.backendagent.sandbox.DockerSandboxExecutor.DEFAULT_IMAGE;
        int testTimeoutSeconds = 120;
        Path dataDirectory = Path.of(".agent-sessions");
        java.util.UUID showSession = null;
        java.util.UUID memoryFrom = null;
        java.util.UUID resumeSession = null;
        var seen = new HashSet<String>();
        for (int i = 0; i < args.length; i += 2) {
            String option = args[i];
            if (!seen.add(option) || i + 1 >= args.length) {
                throw new IllegalArgumentException("Options must be unique and have a value");
            }
            String value = args[i + 1];
            if (value.isBlank() || value.startsWith("--")) {
                throw new IllegalArgumentException("Option value must not be blank or another option");
            }
            switch (option) {
                case "--workspace" -> workspace = Path.of(value);
                case "--task" -> task = value;
                case "--config" -> configFile = Path.of(value);
                case "--data-dir" -> dataDirectory = Path.of(value);
                case "--show-session" -> {
                    try { showSession = java.util.UUID.fromString(value); }
                    catch (IllegalArgumentException failure) {
                        throw new IllegalArgumentException("--show-session must be a valid UUID");
                    }
                }
                case "--resume-session" -> {
                    try { resumeSession = java.util.UUID.fromString(value); }
                    catch (IllegalArgumentException failure) {
                        throw new IllegalArgumentException("--resume-session must be a valid UUID");
                    }
                }
                case "--memory-from" -> {
                    try { memoryFrom = java.util.UUID.fromString(value); }
                    catch (IllegalArgumentException failure) {
                        throw new IllegalArgumentException("--memory-from must be a valid UUID");
                    }
                }
                case "--max-model-calls" -> {
                    try {
                        maxModelCalls = Integer.parseInt(value);
                        if (maxModelCalls <= 0) {
                            throw new NumberFormatException();
                        }
                    } catch (NumberFormatException failure) {
                        throw new IllegalArgumentException("--max-model-calls must be a positive integer");
                    }
                }
                case "--max-history-chars" -> {
                    try {
                        maxHistoryCharacters = Integer.parseInt(value);
                        if (maxHistoryCharacters <= 0) {
                            throw new NumberFormatException();
                        }
                    } catch (NumberFormatException failure) {
                        throw new IllegalArgumentException("--max-history-chars must be a positive integer");
                    }
                }
                case "--sandbox-image" -> {
                    if (!value.matches("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,200}")) {
                        throw new IllegalArgumentException("Invalid sandbox image name");
                    }
                    sandboxImage = value;
                }
                case "--test-timeout-seconds" -> {
                    try {
                        testTimeoutSeconds = Integer.parseInt(value);
                        if (testTimeoutSeconds < 1 || testTimeoutSeconds > 600) { throw new NumberFormatException(); }
                    } catch (NumberFormatException failure) {
                        throw new IllegalArgumentException("--test-timeout-seconds must be between 1 and 600");
                    }
                }
                default -> throw new IllegalArgumentException("Unknown option; see --help");
            }
        }
        if (showSession != null && seen.stream().anyMatch(option -> !option.equals("--show-session")
                && !option.equals("--data-dir"))) {
            throw new IllegalArgumentException("Session query only accepts --show-session and --data-dir");
        }
        if (resumeSession != null && (task != null || memoryFrom != null)) {
            throw new IllegalArgumentException("Resume uses the saved task; --task and --memory-from are not accepted");
        }
        if (showSession == null && (workspace == null || (resumeSession == null && task == null))) {
            throw new IllegalArgumentException("Both --workspace and --task are required");
        }
        return new AgentOptions(workspace, task, configFile, maxModelCalls, maxHistoryCharacters, sandboxImage,
                testTimeoutSeconds, dataDirectory, showSession, memoryFrom, resumeSession);
    }

    public Path getWorkspace() { return workspace; }
    public String getTask() { return task; }
    public Path getConfigFile() { return configFile; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public int getMaxHistoryCharacters() { return maxHistoryCharacters; }
    public String getSandboxImage() { return sandboxImage; }
    public int getTestTimeoutSeconds() { return testTimeoutSeconds; }
    public Path getDataDirectory() { return dataDirectory; }
    public java.util.UUID getShowSession() { return showSession; }
    public java.util.UUID getResumeSession() { return resumeSession; }
    public java.util.UUID getMemoryFrom() { return memoryFrom; }
}
