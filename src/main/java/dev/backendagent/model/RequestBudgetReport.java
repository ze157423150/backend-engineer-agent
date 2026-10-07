package dev.backendagent.model;

/** Local conservative estimate, not the provider's exact tokenizer or billed usage. */
public final class RequestBudgetReport {
    private final long requestBytes;
    private final long estimatedInputTokens;
    private final int outputTokens;
    private final int safetyMarginTokens;
    private final int contextWindowTokens;
    public RequestBudgetReport(long requestBytes, int outputTokens, int safetyMarginTokens, int contextWindowTokens) {
        this.requestBytes = requestBytes;
        this.estimatedInputTokens = requestBytes;
        this.outputTokens = outputTokens;
        this.safetyMarginTokens = safetyMarginTokens;
        this.contextWindowTokens = contextWindowTokens;
    }
    public long getRequestBytes() { return requestBytes; }
    public long getEstimatedInputTokens() { return estimatedInputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public int getSafetyMarginTokens() { return safetyMarginTokens; }
    public int getContextWindowTokens() { return contextWindowTokens; }
    public long getEstimatedTotalTokens() { return estimatedInputTokens + outputTokens + safetyMarginTokens; }
    public boolean isWithinBudget() { return getEstimatedTotalTokens() <= contextWindowTokens; }
}
