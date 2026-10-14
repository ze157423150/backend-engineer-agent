package dev.backendagent.model;

import java.util.List;

/** Bounded response metadata. Argument snippets retain punctuation only, never text values. */
public final class OutputLimitDetails {
    private final int maxOutputTokens;
    private final int contentCharacters;
    private final int reasoningCharacters;
    private final int toolCallCount;
    private final List<ToolArgumentDetails> tools;

    public OutputLimitDetails(int maxOutputTokens, int contentCharacters, int reasoningCharacters,
                              int toolCallCount, List<ToolArgumentDetails> tools) {
        this.maxOutputTokens = maxOutputTokens;
        this.contentCharacters = contentCharacters;
        this.reasoningCharacters = reasoningCharacters;
        this.toolCallCount = toolCallCount;
        this.tools = List.copyOf(tools);
    }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public int getContentCharacters() { return contentCharacters; }
    public int getReasoningCharacters() { return reasoningCharacters; }
    public int getToolCallCount() { return toolCallCount; }
    public List<ToolArgumentDetails> getTools() { return tools; }

    public static final class ToolArgumentDetails {
        private final String name;
        private final int argumentCharacters;
        private final boolean argumentsValidJson;
        private final String headShape;
        private final String tailShape;
        public ToolArgumentDetails(String name, String arguments, boolean argumentsValidJson) {
            this.name = name;
            this.argumentCharacters = arguments.length();
            this.argumentsValidJson = argumentsValidJson;
            this.headShape = shape(arguments.substring(0, Math.min(96, arguments.length())));
            this.tailShape = shape(arguments.substring(Math.max(0, arguments.length() - 96)));
        }
        private static String shape(String text) {
            var result = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                result.append("{}[]():,;\"\\ \n\r\t".indexOf(c) >= 0 ? c : '*');
            }
            return result.toString();
        }
        public String getName() { return name; }
        public int getArgumentCharacters() { return argumentCharacters; }
        public boolean isArgumentsValidJson() { return argumentsValidJson; }
        public String getHeadShape() { return headShape; }
        public String getTailShape() { return tailShape; }
    }
}
