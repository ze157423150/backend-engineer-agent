package dev.backendagent.verification;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.backendagent.model.FileFingerprint;

/** A bounded source excerpt, never a model-written interpretation of the contract. */
public final class VerificationBasis {
    private final String id, sourceCallId, content;
    private final int turnId, startLine, endLine;
    private final FileFingerprint file;
    @JsonCreator
    public VerificationBasis(@JsonProperty("id") String id, @JsonProperty("turnId") int turnId,
            @JsonProperty("sourceCallId") String sourceCallId, @JsonProperty("file") FileFingerprint file,
            @JsonProperty("startLine") int startLine, @JsonProperty("endLine") int endLine,
            @JsonProperty("content") String content) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || turnId < 1 || sourceCallId == null
                || sourceCallId.isBlank() || sourceCallId.length() > 256 || file == null || startLine < 1
                || endLine < startLine || endLine - startLine >= 100 || content == null || content.isBlank()
                || content.length() > 1600) throw new IllegalArgumentException("Invalid verification basis");
        this.id=id; this.turnId=turnId; this.sourceCallId=sourceCallId; this.file=file;
        this.startLine=startLine; this.endLine=endLine; this.content=content;
    }
    public String getId(){return id;} public int getTurnId(){return turnId;}
    public String getSourceCallId(){return sourceCallId;} public FileFingerprint getFile(){return file;}
    public int getStartLine(){return startLine;} public int getEndLine(){return endLine;}
    public String getContent(){return content;}
    public String signature(){return dev.backendagent.history.ArchivedBody.hash(id+"\n"+sourceCallId+"\n"
            +file.getPath()+"\n"+file.getSha256()+"\n"+startLine+":"+endLine+"\n"+content);}
}
