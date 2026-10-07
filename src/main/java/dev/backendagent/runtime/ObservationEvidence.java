package dev.backendagent.runtime;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.backendagent.model.FileFingerprint;

/** Immutable provenance, not a permanent declaration that the observation is current. */
public final class ObservationEvidence {
    public enum Type { FILE_CONTENT }
    public enum Validity { CURRENT, STALE, UNKNOWN }
    private final Type type;
    private final String sourceCallId;
    private final FileFingerprint file;
    private final long workspaceRevision;

    @JsonCreator
    public ObservationEvidence(@JsonProperty("type") Type type,
                               @JsonProperty("sourceCallId") String sourceCallId,
                               @JsonProperty("file") FileFingerprint file,
                               @JsonProperty("workspaceRevision") long workspaceRevision) {
        if (type == null || sourceCallId == null || sourceCallId.isBlank() || file == null || workspaceRevision < 0) {
            throw new IllegalArgumentException("Invalid observation evidence");
        }
        this.type = type;
        this.sourceCallId = sourceCallId;
        this.file = file;
        this.workspaceRevision = workspaceRevision;
    }
    public Type getType() { return type; }
    public String getSourceCallId() { return sourceCallId; }
    public FileFingerprint getFile() { return file; }
    public long getWorkspaceRevision() { return workspaceRevision; }
    @Override public boolean equals(Object other) {
        return other instanceof ObservationEvidence evidence && type == evidence.type
                && sourceCallId.equals(evidence.sourceCallId) && file.equals(evidence.file)
                && workspaceRevision == evidence.workspaceRevision;
    }
    @Override public int hashCode() { return Objects.hash(type, sourceCallId, file, workspaceRevision); }
}
