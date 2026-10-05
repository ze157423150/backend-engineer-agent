package dev.backendagent.memory;

public final class MemoryLoadReport {
    private final int loaded;
    private final int skipped;
    public MemoryLoadReport(int loaded, int skipped) { this.loaded = loaded; this.skipped = skipped; }
    public int getLoaded() { return loaded; }
    public int getSkipped() { return skipped; }
}
