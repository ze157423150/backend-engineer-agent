package dev.backendagent.model;

import java.util.Objects;

public record ToolResult(boolean successful, String content, FileFingerprint fileFingerprint,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         dev.backendagent.history.HistoricalEvidence historicalEvidence,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         FileReadMetadata readMetadata,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         FileFingerprint patchBaseFingerprint) {
    public ToolResult(boolean successful,String content,FileFingerprint fileFingerprint,
                      dev.backendagent.history.HistoricalEvidence historicalEvidence,FileReadMetadata readMetadata) {
        this(successful,content,fileFingerprint,historicalEvidence,readMetadata,null);
    }
    /** Host-side provenance; model request encoding deliberately does not include this field. */
    public ToolResult withPatchBase(FileFingerprint baseline) {
        return successful ? new ToolResult(true,content,fileFingerprint,historicalEvidence,readMetadata,baseline) : this;
    }
    public ToolResult(boolean successful,String content,FileFingerprint fileFingerprint,dev.backendagent.history.HistoricalEvidence historicalEvidence){this(successful,content,fileFingerprint,historicalEvidence,null);}
    public ToolResult(boolean successful, String content, FileFingerprint fileFingerprint) { this(successful, content, fileFingerprint, null); }
    public ToolResult(boolean successful, String content) { this(successful, content, null); }
    public ToolResult {
        Objects.requireNonNull(content);
        if (patchBaseFingerprint != null && (fileFingerprint == null
                || !patchBaseFingerprint.getPath().equals(fileFingerprint.getPath()))) {
            throw new IllegalArgumentException("Patch baseline must match result path");
        }
        if (!successful && (fileFingerprint != null || historicalEvidence != null || readMetadata != null || patchBaseFingerprint != null)) { throw new IllegalArgumentException("Failed result cannot carry a fingerprint"); }
    }
}
