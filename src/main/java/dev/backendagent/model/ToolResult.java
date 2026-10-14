package dev.backendagent.model;

import java.util.Objects;

public record ToolResult(boolean successful, String content, FileFingerprint fileFingerprint,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         dev.backendagent.history.HistoricalEvidence historicalEvidence,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         FileReadMetadata readMetadata) {
    public ToolResult(boolean successful,String content,FileFingerprint fileFingerprint,dev.backendagent.history.HistoricalEvidence historicalEvidence){this(successful,content,fileFingerprint,historicalEvidence,null);}
    public ToolResult(boolean successful, String content, FileFingerprint fileFingerprint) { this(successful, content, fileFingerprint, null); }
    public ToolResult(boolean successful, String content) { this(successful, content, null); }
    public ToolResult {
        Objects.requireNonNull(content);
        if (!successful && (fileFingerprint != null || historicalEvidence != null || readMetadata != null)) { throw new IllegalArgumentException("Failed result cannot carry a fingerprint"); }
    }
}
