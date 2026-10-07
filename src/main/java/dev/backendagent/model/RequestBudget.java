package dev.backendagent.model;

/** Estimates the exact outgoing UTF-8 JSON at one token per byte, plus a framing reserve. */
public final class RequestBudget {
    public static final int DEFAULT_CONTEXT_WINDOW = 65536;
    public static final int DEFAULT_OUTPUT_TOKENS = 2048;
    public static final int DEFAULT_SAFETY_MARGIN = 1024;
    private final int contextWindowTokens;
    private final int outputTokens;
    private final int safetyMarginTokens;
    public RequestBudget(int contextWindowTokens, int outputTokens, int safetyMarginTokens) {
        if (contextWindowTokens <= 0 || outputTokens <= 0 || safetyMarginTokens < 0
                || (long) outputTokens + safetyMarginTokens >= contextWindowTokens) {
            throw new IllegalArgumentException("Request budget must leave positive input space");
        }
        this.contextWindowTokens = contextWindowTokens;
        this.outputTokens = outputTokens;
        this.safetyMarginTokens = safetyMarginTokens;
    }
    public RequestBudgetReport measure(String serializedRequest) {
        return new RequestBudgetReport(serializedRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                outputTokens, safetyMarginTokens, contextWindowTokens);
    }
    public int getOutputTokens() { return outputTokens; }
}
