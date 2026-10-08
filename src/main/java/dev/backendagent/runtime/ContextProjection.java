package dev.backendagent.runtime;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.backendagent.model.ToolExchange;

/** Last assembled model view; sourceHistorySize can precede a subsequently completed tool batch. */
public final class ContextProjection {
    private final int policyVersion;
    private final int sourceHistorySize;
    private final int maxHistoryCharacters;
    private final int omittedExchanges;
    private final long historyCharacters;
    private final int compressedExchanges;
    private final String recoveryReferences;
    private final List<ToolExchange> history;
    private final int filteredExchanges;

    public ContextProjection(int policyVersion, int sourceHistorySize, int maxHistoryCharacters,
            int omittedExchanges, long historyCharacters, int compressedExchanges, String recoveryReferences,
            List<ToolExchange> history) {
        this(policyVersion, sourceHistorySize, maxHistoryCharacters, omittedExchanges, historyCharacters,
                compressedExchanges, recoveryReferences, history, 0);
    }

    @JsonCreator
    public ContextProjection(@JsonProperty("policyVersion") int policyVersion,
            @JsonProperty("sourceHistorySize") int sourceHistorySize,
            @JsonProperty("maxHistoryCharacters") int maxHistoryCharacters,
            @JsonProperty("omittedExchanges") int omittedExchanges,
            @JsonProperty("historyCharacters") long historyCharacters,
            @JsonProperty("compressedExchanges") int compressedExchanges,
            @JsonProperty("recoveryReferences") String recoveryReferences,
            @JsonProperty("history") List<ToolExchange> history,
            @JsonProperty("filteredExchanges") int filteredExchanges) {
        if ((policyVersion < 1 || policyVersion > 4) || sourceHistorySize < 0 || maxHistoryCharacters <= 0
                || omittedExchanges < 0 || historyCharacters < 0 || historyCharacters > maxHistoryCharacters
                || compressedExchanges < 0 || recoveryReferences == null || history == null
                || omittedExchanges + history.size() != sourceHistorySize
                || compressedExchanges > history.size() || filteredExchanges < 0 || filteredExchanges > history.size()
                || (policyVersion == 1 && filteredExchanges != 0)) {
            throw new IllegalArgumentException("Invalid context projection");
        }
        this.policyVersion = policyVersion;
        this.sourceHistorySize = sourceHistorySize;
        this.maxHistoryCharacters = maxHistoryCharacters;
        this.omittedExchanges = omittedExchanges;
        this.historyCharacters = historyCharacters;
        this.compressedExchanges = compressedExchanges;
        this.recoveryReferences = recoveryReferences;
        this.history = List.copyOf(history);
        this.filteredExchanges = filteredExchanges;
    }

    public void validateAgainst(List<ToolExchange> raw) {
        if (sourceHistorySize > raw.size()
                || (sourceHistorySize > 0 && sourceHistorySize < raw.size()
                    && raw.get(sourceHistorySize - 1).modelCallNumber() > 0
                    && raw.get(sourceHistorySize - 1).modelCallNumber() == raw.get(sourceHistorySize).modelCallNumber())) {
            throw new IllegalArgumentException("Projection source does not end at a complete batch");
        }
        var expected = new ContextAssembler(new ContextBudget(maxHistoryCharacters))
                .project(raw.subList(0, sourceHistorySize), policyVersion);
        if (!history.equals(expected.history) || omittedExchanges != expected.omittedExchanges
                || historyCharacters != expected.historyCharacters
                || compressedExchanges != expected.compressedExchanges
                || filteredExchanges != expected.filteredExchanges
                || !recoveryReferences.equals(expected.recoveryReferences)) {
            throw new IllegalArgumentException("Projection does not match original observations");
        }
    }

    public int getPolicyVersion() { return policyVersion; }
    public int getSourceHistorySize() { return sourceHistorySize; }
    public int getMaxHistoryCharacters() { return maxHistoryCharacters; }
    public int getOmittedExchanges() { return omittedExchanges; }
    public long getHistoryCharacters() { return historyCharacters; }
    public int getCompressedExchanges() { return compressedExchanges; }
    public String getRecoveryReferences() { return recoveryReferences; }
    public List<ToolExchange> getHistory() { return history; }
    public int getFilteredExchanges() { return filteredExchanges; }
}
