package dev.backendagent.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Read coordinates are metadata, never part of the copyable source text. */
public final class FileReadMetadata {
    private final int totalLines, startLine, endLine;
    private final boolean reachedEndOfFile, bodyComplete, previouslyRead;
    private final String format;
    public FileReadMetadata(int totalLines, int startLine, int endLine, boolean reachedEndOfFile, boolean bodyComplete, boolean previouslyRead) {
        this(totalLines,startLine,endLine,reachedEndOfFile,bodyComplete,previouslyRead,null);
    }
    @JsonCreator
    public FileReadMetadata(@JsonProperty("totalLines") int totalLines,
            @JsonProperty("startLine") int startLine, @JsonProperty("endLine") int endLine,
            @JsonProperty("reachedEndOfFile") boolean reachedEndOfFile,
            @JsonProperty("bodyComplete") boolean bodyComplete,
            @JsonProperty("previouslyRead") boolean previouslyRead,
            @JsonProperty("format") String format) {
        if(totalLines<1 || startLine<1 || endLine<startLine || endLine>totalLines) throw new IllegalArgumentException("Invalid read range");
        if(format!=null && !format.equals("raw") && !format.equals("numbered"))throw new IllegalArgumentException("Invalid read format");
        this.format=format;
        this.totalLines=totalLines;this.startLine=startLine;this.endLine=endLine;
        this.reachedEndOfFile=reachedEndOfFile;this.bodyComplete=bodyComplete;this.previouslyRead=previouslyRead;
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String getFormat(){return format;}
    /** Explicit result metadata wins over legacy request arguments. */
    public boolean usesRawText(String requestedFormat){return "raw".equals(format==null?requestedFormat:format);}
    public int getTotalLines(){return totalLines;}
    public int getStartLine(){return startLine;}
    public int getEndLine(){return endLine;}
    public boolean isReachedEndOfFile(){return reachedEndOfFile;}
    public boolean isBodyComplete(){return bodyComplete;}
    public boolean isPreviouslyRead(){return previouslyRead;}
    public FileReadMetadata repeated(boolean repeated){return new FileReadMetadata(totalLines,startLine,endLine,reachedEndOfFile,bodyComplete,repeated,format);}
    public FileReadMetadata excerpt(){return new FileReadMetadata(totalLines,startLine,endLine,reachedEndOfFile,false,previouslyRead,format);}
    @Override public boolean equals(Object other){return other instanceof FileReadMetadata m && totalLines==m.totalLines && startLine==m.startLine && endLine==m.endLine && reachedEndOfFile==m.reachedEndOfFile && bodyComplete==m.bodyComplete && previouslyRead==m.previouslyRead && java.util.Objects.equals(format,m.format);}
    @Override public int hashCode(){return java.util.Objects.hash(totalLines,startLine,endLine,reachedEndOfFile,bodyComplete,previouslyRead,format);}
}
