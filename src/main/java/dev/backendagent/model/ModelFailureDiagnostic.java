package dev.backendagent.model;

/** Safe metadata only: no response text, argument values, headers or credentials. */
public final class ModelFailureDiagnostic {
    public enum Code { OUTPUT_LIMIT, RESPONSE_INTERRUPTED, INVALID_ENVELOPE, INVALID_JSON,
        INVALID_TOOL_ARGUMENTS, UNDECLARED_TOOL, EMPTY_ANSWER, TIMEOUT, TRANSPORT, HTTP_ERROR, INTERRUPTED }
    public enum FinishReason { STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, ABORTED,
        INSUFFICIENT_SYSTEM_RESOURCE, OTHER, MISSING }
    private final Code code;
    private final FinishReason finishReason;
    private final Integer httpStatus;
    private final boolean retryable;
    private final OutputLimitDetails outputLimit;
    public ModelFailureDiagnostic(Code code, FinishReason finishReason, Integer httpStatus, boolean retryable) {
        this(code, finishReason, httpStatus, retryable, null);
    }
    public ModelFailureDiagnostic(Code code, FinishReason finishReason, Integer httpStatus, boolean retryable,
                                  OutputLimitDetails outputLimit) {
        this.code = java.util.Objects.requireNonNull(code);
        this.finishReason = java.util.Objects.requireNonNull(finishReason);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.outputLimit = outputLimit;
    }
    public Code getCode() { return code; }
    public FinishReason getFinishReason() { return finishReason; }
    public Integer getHttpStatus() { return httpStatus; }
    public boolean isRetryable() { return retryable; }
    public OutputLimitDetails getOutputLimit() { return outputLimit; }
    public String detail() {
        return "code=" + code + ", finishReason=" + finishReason + ", httpStatus=" + httpStatus
                + ", retryable=" + retryable;
    }
}
