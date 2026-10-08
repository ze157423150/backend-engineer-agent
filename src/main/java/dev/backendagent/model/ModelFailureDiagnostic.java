package dev.backendagent.model;

/** Safe metadata only: no response text, argument values, headers or credentials. */
public final class ModelFailureDiagnostic {
    public enum Code { OUTPUT_LIMIT, RESPONSE_INTERRUPTED, INVALID_ENVELOPE, INVALID_JSON,
        INVALID_TOOL_ARGUMENTS, EMPTY_ANSWER, TIMEOUT, TRANSPORT, HTTP_ERROR, INTERRUPTED }
    public enum FinishReason { STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, ABORTED,
        INSUFFICIENT_SYSTEM_RESOURCE, OTHER, MISSING }
    private final Code code;
    private final FinishReason finishReason;
    private final Integer httpStatus;
    private final boolean retryable;
    public ModelFailureDiagnostic(Code code, FinishReason finishReason, Integer httpStatus, boolean retryable) {
        this.code = java.util.Objects.requireNonNull(code);
        this.finishReason = java.util.Objects.requireNonNull(finishReason);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }
    public Code getCode() { return code; }
    public FinishReason getFinishReason() { return finishReason; }
    public Integer getHttpStatus() { return httpStatus; }
    public boolean isRetryable() { return retryable; }
    public String detail() {
        return "code=" + code + ", finishReason=" + finishReason + ", httpStatus=" + httpStatus
                + ", retryable=" + retryable;
    }
}
