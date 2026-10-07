package dev.backendagent.model;

/** Provider-reported usage is nullable; absent usage is never replaced with an estimate. No credentials or response text. */
public final class ModelCallMetrics {
    private final String kind;
    private final long requestBytes;
    private final long elapsedMillis;
    private final int httpStatus;
    private final Long promptTokens;
    private final Long completionTokens;
    private final Long totalTokens;
    public ModelCallMetrics(String kind, long requestBytes, long elapsedMillis, int httpStatus,
            Long promptTokens, Long completionTokens, Long totalTokens) {
        this.kind = kind; this.requestBytes = requestBytes; this.elapsedMillis = elapsedMillis;
        this.httpStatus = httpStatus; this.promptTokens = promptTokens;
        this.completionTokens = completionTokens; this.totalTokens = totalTokens;
    }
    public String getKind() { return kind; }
    public long getRequestBytes() { return requestBytes; }
    public long getElapsedMillis() { return elapsedMillis; }
    public int getHttpStatus() { return httpStatus; }
    public Long getPromptTokens() { return promptTokens; }
    public Long getCompletionTokens() { return completionTokens; }
    public Long getTotalTokens() { return totalTokens; }
}
