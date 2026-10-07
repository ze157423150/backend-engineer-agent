package dev.backendagent.history;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.backendagent.model.FileFingerprint;
import dev.backendagent.model.ToolExchange;
import dev.backendagent.runtime.ObservationEvidence.Validity;
import java.util.Objects;

/** A bounded original excerpt for historical questions; never authorizes current-state claims. */
public final class HistoricalEvidence {
    public enum UsageScope { HISTORICAL_ONLY }
    public static final int MAX_CONTENT_CHARACTERS = 1600;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final String sourceCallId, sourceTool, sourceBodySha256, content;
    private final long sourceEventSequence;
    private final FileFingerprint sourceFile;
    private final Validity validityAtRetrieval;
    private final Long observedRevision;
    private final int startLine, endLine;
    private final boolean truncated;

    @JsonCreator
    public HistoricalEvidence(@JsonProperty("sourceCallId") String sourceCallId,
            @JsonProperty("sourceTool") String sourceTool, @JsonProperty("sourceEventSequence") long sourceEventSequence,
            @JsonProperty("sourceFile") FileFingerprint sourceFile,
            @JsonProperty("sourceBodySha256") String sourceBodySha256,
            @JsonProperty("validityAtRetrieval") Validity validityAtRetrieval,
            @JsonProperty("observedRevision") Long observedRevision,
            @JsonProperty("startLine") int startLine, @JsonProperty("endLine") int endLine,
            @JsonProperty("truncated") boolean truncated, @JsonProperty("content") String content,
            @JsonProperty("usageScope") UsageScope usageScope) {
        if (sourceCallId == null || sourceCallId.isBlank() || sourceCallId.length() > 256
                || sourceTool == null || sourceTool.isBlank() || sourceTool.length() > 100 || sourceEventSequence < 1
                || sourceBodySha256 == null || !sourceBodySha256.matches("[0-9a-f]{64}") || validityAtRetrieval == null
                || (observedRevision != null && observedRevision < 0) || startLine < 1 || endLine < startLine
                || (long) endLine - startLine + 1 > 200 || content == null || content.isEmpty()
                || content.length() > MAX_CONTENT_CHARACTERS || usageScope != UsageScope.HISTORICAL_ONLY) {
            throw new IllegalArgumentException("Invalid historical evidence");
        }
        this.sourceCallId = sourceCallId; this.sourceTool = sourceTool; this.sourceEventSequence = sourceEventSequence;
        this.sourceFile = sourceFile; this.sourceBodySha256 = sourceBodySha256; this.validityAtRetrieval = validityAtRetrieval;
        this.observedRevision = observedRevision; this.startLine = startLine; this.endLine = endLine;
        this.truncated = truncated; this.content = content;
    }
    public static HistoricalEvidence excerpt(ToolExchange source, long sequence, Validity validity, int start, int end) {
        // Do not cache wrappers of other historical extractions: keep direct original provenance.
        if (!source.result().successful() || source.call().name().equals("read_observation") || source.call().name().equals("search_history")) { return null; }
        String[] lines = source.result().content().split("\\R", -1);
        int last = Math.min(end, lines.length), selectedEnd = start - 1;
        var text = new StringBuilder();
        for (int i = start - 1; i < last; i++) {
            String line = (i + 1) + ": " + lines[i] + "\n";
            if (text.length() + line.length() > MAX_CONTENT_CHARACTERS) { break; }
            text.append(line); selectedEnd = i + 1;
        }
        if (text.isEmpty()) { return null; }
        return new HistoricalEvidence(source.call().id(), source.call().name(), sequence, source.result().fileFingerprint(),
                ArchivedBody.hash(source.result().content()), validity, source.evidence() == null ? null : source.evidence().getWorkspaceRevision(),
                start, selectedEnd, selectedEnd < last, text.toString(), UsageScope.HISTORICAL_ONLY);
    }
    public void validateAgainst(ToolExchange source, ToolExchange retrieval) {
        if (source == null || !sourceCallId.equals(retrieval.call().arguments().get("call_id"))
                || !retrieval.call().name().equals("read_observation") || !retrieval.result().successful()
                || !equals(retrieval.result().historicalEvidence()) || !retrieval.result().content().contains(content)) {
            throw new IllegalArgumentException("Historical evidence has no successful extraction");
        }
        int start = Integer.parseInt(retrieval.call().arguments().get("start_line"));
        int end = Integer.parseInt(retrieval.call().arguments().get("end_line"));
        if (!equals(excerpt(source, sourceEventSequence, validityAtRetrieval, start, end))) {
            throw new IllegalArgumentException("Historical evidence differs from original excerpt");
        }
    }
    public String getSourceCallId() { return sourceCallId; }
    public String getSourceTool() { return sourceTool; }
    public long getSourceEventSequence() { return sourceEventSequence; }
    public FileFingerprint getSourceFile() { return sourceFile; }
    public String getSourceBodySha256() { return sourceBodySha256; }
    public Validity getValidityAtRetrieval() { return validityAtRetrieval; }
    public Long getObservedRevision() { return observedRevision; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public boolean isTruncated() { return truncated; }
    public String getContent() { return content; }
    public UsageScope getUsageScope() { return UsageScope.HISTORICAL_ONLY; }
    public int characterCount() {
        try { return JSON.writeValueAsString(this).length(); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot measure historical evidence"); }
    }
    @Override public boolean equals(Object other) {
        return other instanceof HistoricalEvidence e && sourceCallId.equals(e.sourceCallId) && sourceTool.equals(e.sourceTool)
                && sourceEventSequence == e.sourceEventSequence && Objects.equals(sourceFile, e.sourceFile)
                && sourceBodySha256.equals(e.sourceBodySha256) && validityAtRetrieval == e.validityAtRetrieval
                && Objects.equals(observedRevision, e.observedRevision) && startLine == e.startLine && endLine == e.endLine
                && truncated == e.truncated && content.equals(e.content);
    }
    @Override public int hashCode() { return Objects.hash(sourceCallId, sourceEventSequence, sourceBodySha256, startLine, endLine, content); }
}
