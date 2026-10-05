package dev.backendagent.sandbox;

public final class CommandResult {
    private final int exitCode;
    private final boolean timedOut;
    private final String output;

    public CommandResult(int exitCode, boolean timedOut, String output) {
        this.exitCode = exitCode;
        this.timedOut = timedOut;
        this.output = output;
    }
    public int getExitCode() { return exitCode; }
    public boolean isTimedOut() { return timedOut; }
    public String getOutput() { return output; }
}
