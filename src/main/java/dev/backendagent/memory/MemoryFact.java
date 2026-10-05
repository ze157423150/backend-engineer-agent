package dev.backendagent.memory;

/** Model-written statement paired with an exact excerpt from a successful tool result. */
public final class MemoryFact {
    private final String statement;
    private final String sourceCallId;
    private final String sourceTool;
    private final String sourcePath;
    private final String evidenceQuote;

    @com.fasterxml.jackson.annotation.JsonCreator
    public MemoryFact(@com.fasterxml.jackson.annotation.JsonProperty("statement") String statement,
                      @com.fasterxml.jackson.annotation.JsonProperty("sourceCallId") String sourceCallId,
                      @com.fasterxml.jackson.annotation.JsonProperty("sourceTool") String sourceTool,
                      @com.fasterxml.jackson.annotation.JsonProperty("sourcePath") String sourcePath,
                      @com.fasterxml.jackson.annotation.JsonProperty("evidenceQuote") String evidenceQuote) {
        this.statement = checked(statement, 300);
        this.sourceCallId = checked(sourceCallId, 200);
        this.sourceTool = checked(sourceTool, 100);
        this.sourcePath = checked(sourcePath, 500);
        this.evidenceQuote = checked(evidenceQuote, 400);
    }

    private static String checked(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException("Memory field is blank or exceeds its character limit");
        }
        return value;
    }

    public String getStatement() { return statement; }
    public String getSourceCallId() { return sourceCallId; }
    public String getSourceTool() { return sourceTool; }
    public String getSourcePath() { return sourcePath; }
    public String getEvidenceQuote() { return evidenceQuote; }

    public int characterCount() {
        return statement.length() + sourceCallId.length() + sourceTool.length()
                + sourcePath.length() + evidenceQuote.length();
    }
}
