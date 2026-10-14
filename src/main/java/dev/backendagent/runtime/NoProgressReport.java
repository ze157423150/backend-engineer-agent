package dev.backendagent.runtime;

/** Runtime control metadata, separate from model-written summaries and historical evidence. */
public final class NoProgressReport {
    private final int consecutiveBatches;
    private final int warningLevel;
    private final long workspaceRevision;
    private final java.util.Map<String, Integer> patchFailures;

    public NoProgressReport(int consecutiveBatches, long workspaceRevision) {
        this(consecutiveBatches, workspaceRevision, java.util.Map.of());
    }
    public NoProgressReport(int consecutiveBatches, long workspaceRevision, java.util.Map<String, Integer> patchFailures) {
        this.consecutiveBatches = consecutiveBatches;
        this.patchFailures = java.util.Map.copyOf(patchFailures);
        this.warningLevel = this.patchFailures.values().stream().anyMatch(n -> n >= 3) ? 2
                : consecutiveBatches >= 8 ? 2 : consecutiveBatches >= 4 ? 1 : 0;
        this.workspaceRevision = workspaceRevision;
    }
    public int getConsecutiveBatches() { return consecutiveBatches; }
    public int getWarningLevel() { return warningLevel; }
    public long getWorkspaceRevision() { return workspaceRevision; }
    public java.util.Map<String, Integer> getPatchFailures() { return patchFailures; }
    public boolean shouldStop() { return consecutiveBatches >= 12 || patchFailures.values().stream().anyMatch(n -> n >= 4); }
}
