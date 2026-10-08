package dev.backendagent.cli;

import java.nio.file.Path;
import java.util.HashSet;

/** User-supplied task and workspace, separate from model credentials. */
public final class AgentOptions {
    public static final String USAGE = """
            Usage: java -jar <jar> --interactive --workspace <directory>
                   [--task <first-message>] [--config <properties-file>] [--max-model-calls <total-limit>]
                   [--continue-session <uuid>] [--data-dir <directory>]
                   java -jar <jar> --workspace <directory> --task <objective>
                   [--config <properties-file>] [--max-model-calls <positive-integer>]
                   [--max-history-chars <positive-integer>] [--min-summary-input-chars <positive-integer>]
                   [--sandbox-image <local-image>] [--test-timeout-seconds <1-600>]
                   [--data-dir <directory>] [--memory-from <uuid>]
                   java -jar <jar> --resume-session <uuid> --workspace <directory>
                   [--max-model-calls <total-limit>] [--config <properties-file>] [--data-dir <directory>]
                   java -jar <jar> --resume-failed-session <uuid> --workspace <directory>
                   [--max-model-calls <total-limit>] [--retry-incomplete-tool true]
                   [--config <properties-file>] [--data-dir <directory>]
                   java -jar <jar> --continue-session <uuid> --message <text> --workspace <directory>
                   [--max-model-calls <total-limit>] [--config <properties-file>] [--data-dir <directory>]
                   java -jar <jar> --show-session <uuid> [--data-dir <directory>]
                   java -jar <jar> --export-session <uuid> --output-workspace <new-directory> [--data-dir <directory>]
                   java -jar <jar> --help
            """;

    private boolean interactive;
    private java.util.UUID continueSession;
    private java.util.UUID resumeFailedSession;
    private boolean retryIncompleteTool;
    private String message;
    private final Path workspace;
    private final String task;
    private final Path configFile;
    private final int maxModelCalls;
    private final int maxHistoryCharacters;
    private final int minimumSummaryInputCharacters;
    private final String sandboxImage;
    private final int testTimeoutSeconds;
    private final Path dataDirectory;
    private final java.util.UUID showSession;
    private final java.util.UUID memoryFrom;
    private final java.util.UUID resumeSession;
    private final java.util.UUID exportSession;
    private final Path outputWorkspace;

    private AgentOptions(Path workspace, String task, Path configFile, int maxModelCalls, int maxHistoryCharacters, int minimumSummaryInputCharacters,
                         String sandboxImage, int testTimeoutSeconds, Path dataDirectory, java.util.UUID showSession,
                         java.util.UUID memoryFrom, java.util.UUID resumeSession, java.util.UUID exportSession, Path outputWorkspace) {
        this.workspace = workspace;
        this.task = task;
        this.configFile = configFile;
        this.maxModelCalls = maxModelCalls;
        this.maxHistoryCharacters = maxHistoryCharacters;
        this.minimumSummaryInputCharacters = minimumSummaryInputCharacters;
        this.sandboxImage = sandboxImage;
        this.testTimeoutSeconds = testTimeoutSeconds;
        this.dataDirectory = dataDirectory;
        this.showSession = showSession;
        this.memoryFrom = memoryFrom;
        this.resumeSession = resumeSession;
        this.exportSession = exportSession;
        this.outputWorkspace = outputWorkspace;
    }

    public static AgentOptions parse(String[] args) {
        Path workspace = null;
        String task = null;
        Path configFile = null;
        int maxModelCalls = 20;
        int maxHistoryCharacters = dev.backendagent.runtime.ContextBudget.DEFAULT_MAX_HISTORY_CHARACTERS;
        int minimumSummaryInputCharacters = dev.backendagent.runtime.ContextCompactor.DEFAULT_MIN_INPUT_CHARACTERS;
        String sandboxImage = dev.backendagent.sandbox.DockerSandboxExecutor.DEFAULT_IMAGE;
        int testTimeoutSeconds = 120;
        Path dataDirectory = Path.of(".agent-sessions");
        java.util.UUID showSession = null;
        java.util.UUID memoryFrom = null;
        java.util.UUID resumeSession = null;
        java.util.UUID continueSession = null;
        java.util.UUID resumeFailedSession = null;
        boolean retryIncompleteTool = false;
        String message = null;
        java.util.UUID exportSession = null;
        Path outputWorkspace = null;
        boolean interactive = false;
        var seen = new HashSet<String>();
        for (int i = 0; i < args.length;) {
            String option = args[i];
            if (!seen.add(option)) throw new IllegalArgumentException("Options must be unique");
            if (option.equals("--interactive")) { interactive = true; i++; continue; }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Options must be unique and have a value");
            }
            String value = args[i + 1];
            if (value.isBlank() || value.startsWith("--")) {
                throw new IllegalArgumentException("Option value must not be blank or another option");
            }
            switch (option) {
                case "--resume-failed-session" -> {
                    try { resumeFailedSession = java.util.UUID.fromString(value); }
                    catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("--resume-failed-session must be a valid UUID"); }
                }
                case "--retry-incomplete-tool" -> {
                    if (!value.equals("true")) throw new IllegalArgumentException("--retry-incomplete-tool only accepts true");
                    retryIncompleteTool = true;
                }
                case "--continue-session" -> {
                    try { continueSession = java.util.UUID.fromString(value); }
                    catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("--continue-session must be a valid UUID"); }
                }
                case "--message" -> message = value;
                case "--workspace" -> workspace = Path.of(value);
                case "--task" -> task = value;
                case "--config" -> configFile = Path.of(value);
                case "--data-dir" -> dataDirectory = Path.of(value);
                case "--output-workspace" -> outputWorkspace = Path.of(value);
                case "--export-session" -> {
                    try { exportSession = java.util.UUID.fromString(value); }
                    catch (IllegalArgumentException failure) { throw new IllegalArgumentException("--export-session must be a valid UUID"); }
                }
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
                case "--min-summary-input-chars" -> {
                    try {
                        minimumSummaryInputCharacters = Integer.parseInt(value);
                        if (minimumSummaryInputCharacters <= 0) throw new NumberFormatException();
                    } catch (NumberFormatException failure) {
                        throw new IllegalArgumentException("--min-summary-input-chars must be a positive integer");
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
            i += 2;
        }
        if (exportSession != null && (outputWorkspace == null || seen.stream().anyMatch(option ->
                !java.util.Set.of("--export-session", "--output-workspace", "--data-dir").contains(option)))) {
            throw new IllegalArgumentException("Export only accepts --export-session, --output-workspace and --data-dir");
        }
        if (outputWorkspace != null && exportSession == null) { throw new IllegalArgumentException("--output-workspace requires --export-session"); }
        if (showSession != null && seen.stream().anyMatch(option -> !option.equals("--show-session")
                && !option.equals("--data-dir"))) {
            throw new IllegalArgumentException("Session query only accepts --show-session and --data-dir");
        }
        if ((message != null && continueSession == null) || (continueSession != null && message == null && !interactive))
            throw new IllegalArgumentException("--continue-session requires --message outside interactive mode");
        if (interactive && task != null && task.length() > 8000) throw new IllegalArgumentException("First message exceeds 8000 characters");
        if (continueSession != null && (resumeSession != null || resumeFailedSession != null || task != null || memoryFrom != null || showSession != null || exportSession != null)) {
            throw new IllegalArgumentException("Continue accepts a new --message, not --task, resume, memory or query options");
        }
        if (message != null && message.length() > 8000) throw new IllegalArgumentException("User message exceeds 8000 characters");
        if (resumeSession != null && (task != null || memoryFrom != null)) {
            throw new IllegalArgumentException("Resume uses the saved task; --task and --memory-from are not accepted");
        }
        if (resumeFailedSession != null && (resumeSession != null || task != null || memoryFrom != null
                || continueSession != null || message != null || showSession != null || exportSession != null)) {
            throw new IllegalArgumentException("Failed resume uses the saved turn; no task, message or other session mode is accepted");
        }
        if (retryIncompleteTool && resumeFailedSession == null) throw new IllegalArgumentException("Tool retry requires --resume-failed-session");
        if (showSession == null && exportSession == null && (workspace == null || (resumeSession == null && resumeFailedSession == null && continueSession == null && task == null && !interactive))) {
            throw new IllegalArgumentException("Both --workspace and --task are required");
        }
        var options = new AgentOptions(workspace, task, configFile, maxModelCalls, maxHistoryCharacters, minimumSummaryInputCharacters, sandboxImage,
                testTimeoutSeconds, dataDirectory, showSession, memoryFrom, resumeSession, exportSession, outputWorkspace);
        options.interactive = interactive;
        options.continueSession = continueSession; options.message = message;
        options.resumeFailedSession = resumeFailedSession; options.retryIncompleteTool = retryIncompleteTool;
        return options;
    }

    public boolean isInteractive() { return interactive; }
    public java.util.UUID getContinueSession() { return continueSession; }
    public java.util.UUID getResumeFailedSession() { return resumeFailedSession; }
    public boolean isRetryIncompleteTool() { return retryIncompleteTool; }
    public String getMessage() { return message; }
    public Path getWorkspace() { return workspace; }
    public String getTask() { return task; }
    public Path getConfigFile() { return configFile; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public int getMaxHistoryCharacters() { return maxHistoryCharacters; }
    public int getMinimumSummaryInputCharacters() { return minimumSummaryInputCharacters; }
    public String getSandboxImage() { return sandboxImage; }
    public int getTestTimeoutSeconds() { return testTimeoutSeconds; }
    public Path getDataDirectory() { return dataDirectory; }
    public java.util.UUID getExportSession() { return exportSession; }
    public Path getOutputWorkspace() { return outputWorkspace; }
    public java.util.UUID getShowSession() { return showSession; }
    public java.util.UUID getResumeSession() { return resumeSession; }
    public java.util.UUID getMemoryFrom() { return memoryFrom; }
}
