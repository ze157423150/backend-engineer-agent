package dev.backendagent.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Read coordinates are metadata, never part of the copyable source text. */
public final class FileReadMetadata {
    private final int totalLines, startLine, endLine;
    private final boolean reachedEndOfFile, bodyComplete, previouslyRead;
    @JsonCreator
    public FileReadMetadata(@JsonProperty("totalLines") int totalLines,
            @JsonProperty("startLine") int startLine, @JsonProperty("endLine") int endLine,
            @JsonProperty("reachedEndOfFile") boolean reachedEndOfFile,
            @JsonProperty("bodyComplete") boolean bodyComplete,
            @JsonProperty("previouslyRead") boolean previouslyRead) {
        if(totalLines<1 || startLine<1 || endLine<startLine || endLine>totalLines) throw new IllegalArgumentException("Invalid read range");
        this.totalLines=totalLines;this.startLine=startLine;this.endLine=endLine;
        this.reachedEndOfFile=reachedEndOfFile;this.bodyComplete=bodyComplete;this.previouslyRead=previouslyRead;
    }
    public int getTotalLines(){return totalLines;}
    public int getStartLine(){return startLine;}
    public int getEndLine(){return endLine;}
    public boolean isReachedEndOfFile(){return reachedEndOfFile;}
    public boolean isBodyComplete(){return bodyComplete;}
    public boolean isPreviouslyRead(){return previouslyRead;}
    public FileReadMetadata repeated(boolean repeated){return new FileReadMetadata(totalLines,startLine,endLine,reachedEndOfFile,bodyComplete,repeated);}
    public FileReadMetadata excerpt(){return new FileReadMetadata(totalLines,startLine,endLine,reachedEndOfFile,false,previouslyRead);}
    @Override public boolean equals(Object other){return other instanceof FileReadMetadata m && totalLines==m.totalLines && startLine==m.startLine && endLine==m.endLine && reachedEndOfFile==m.reachedEndOfFile && bodyComplete==m.bodyComplete && previouslyRead==m.previouslyRead;}
    @Override public int hashCode(){return java.util.Objects.hash(totalLines,startLine,endLine,reachedEndOfFile,bodyComplete,previouslyRead);}
}
