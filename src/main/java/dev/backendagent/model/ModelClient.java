package dev.backendagent.model;

@FunctionalInterface
public interface ModelClient {
    ModelResponse execute(ModelRequest request);
    default RequestBudgetReport inspectRequest(ModelRequest request) { return null; }
    default boolean supportsConversationSummarization() { return false; }
    default java.util.List<dev.backendagent.runtime.SummaryNote> summarizeConversation(ConversationSummaryRequest request) {
        throw new UnsupportedOperationException("Conversation summaries are not supported");
    }
    default boolean supportsSummarization() { return false; }
    default java.util.List<dev.backendagent.runtime.SummaryNote> summarize(SummaryRequest request) {
        throw new UnsupportedOperationException("Summary calls are not supported by this model client");
    }
}
