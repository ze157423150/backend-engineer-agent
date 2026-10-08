package dev.backendagent.runtime;

/** Safe diagnostic payload: never persists arbitrary exception messages or model responses. */
public final class SummaryRejection {
    public enum Stage { MODEL_SUMMARY, SUMMARY_VALIDATION, DEDUPLICATION, REQUEST_BUDGET }

    private final Stage stage;
    private final dev.backendagent.model.ModelFailureDiagnostic modelDiagnostic;
    private final String reasonCode;
    private final String reason;
    private final String exceptionType;
    private final int modelCallNumber;
    private final int fromIndex;
    private final int toIndex;

    private SummaryRejection(Stage stage, String code, String reason, RuntimeException failure,
                             int call, int from, int to) {
        this.stage = stage;
        this.modelDiagnostic = failure instanceof dev.backendagent.model.ModelCallFailure modelFailure
                ? modelFailure.getDiagnostic() : null;
        this.reasonCode = code;
        this.reason = reason;
        this.exceptionType = failure.getClass().getSimpleName();
        this.modelCallNumber = call;
        this.fromIndex = from;
        this.toIndex = to;
    }

    public static SummaryRejection from(Stage stage, RuntimeException failure, int call, int from, int to) {
        // Exact allowlist of application-owned messages; unknown messages are not copied to logs.
        String code = switch (failure.getMessage() == null ? "" : failure.getMessage()) {
            case "Invalid summary JSON syntax" -> "INVALID_JSON_SYNTAX";
            case "Invalid summary JSON structure" -> "INVALID_JSON_STRUCTURE";
            case "Invalid summary note fields", "Model response is missing a required text field",
                 "Model response has an invalid text field" -> "INVALID_FIELDS";
            case "Invalid summary note kind" -> "INVALID_NOTE_KIND";
            case "Invalid summary note" -> "INVALID_NOTE_VALUES";
            case "New summaries cannot contain NEXT_STEP" -> "NEXT_STEP_FORBIDDEN";
            case "Summary response exceeds size limit" -> "RESPONSE_TOO_LARGE";
            case "Invalid context summary" -> "INVALID_SUMMARY";
            case "Summary exceeds character limit" -> "SUMMARY_TOO_LARGE";
            case "Summary does not cover complete batches" -> "INCOMPLETE_BATCH_COVERAGE";
            case "Summary quotation has no original conversation message" -> "QUOTE_NOT_IN_MESSAGE";
            case "Summary quotation has no original observation" -> "QUOTE_NOT_IN_ORIGINAL";
            case "Summary source is outside the middle window" -> "SOURCE_OUTSIDE_WINDOW";
            case "Summary cannot promote stale evidence" -> "STALE_EVIDENCE";
            case "Summary quote was not in summary input" -> "QUOTE_NOT_IN_INPUT";
            case "Summary exceeds reserved budget" -> "SUMMARY_BUDGET_EXCEEDED";
            case "Summary does not reduce replaced context" -> "NO_COMPRESSION_GAIN";
            case "DeepSeek request timed out" -> "MODEL_TIMEOUT";
            case "DeepSeek request interrupted" -> "MODEL_INTERRUPTED";
            case "DeepSeek transport or JSON error", "DeepSeek summary transport or JSON error" -> "TRANSPORT_OR_ENVELOPE_JSON_ERROR";
            case "Expected exactly one model response choice", "Model response incomplete or interrupted",
                 "Expected an assistant message", "Thinking mode is not supported in this increment",
                 "Invalid tool_calls field", "Expected at least one tool call", "Unsupported tool call type",
                 "Tool arguments must be a JSON object", "Tool arguments must be strings in this increment",
                 "Tool arguments do not match the tool definition", "Tool calls do not match finish_reason",
                 "Model returned an empty final answer" -> "INVALID_RESPONSE_ENVELOPE";
            default -> stage == Stage.REQUEST_BUDGET ? "REQUEST_BUDGET_CHECK_FAILED" : "UNCLASSIFIED_FAILURE";
        };
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.matches("DeepSeek HTTP [1-5][0-9]{2}; check API key, balance, model availability and request limits")) {
            code = "MODEL_HTTP_ERROR";
        } else if (message.matches("Full request exceeds configured token estimate budget: estimatedTotal=[0-9]+, limit=[0-9]+; reduce input or adjust model.context-window-tokens to a supported value")) {
            code = "MODEL_REQUEST_BUDGET_EXCEEDED";
        }
        if (failure instanceof dev.backendagent.model.ModelCallFailure modelFailure) {
            code = "MODEL_" + modelFailure.getDiagnostic().getCode().name();
        }
        return new SummaryRejection(stage, code, switch (code) {
            case "QUOTE_NOT_IN_MESSAGE" -> "引用不是对应用户消息或回答中的连续原文";
            case "QUOTE_NOT_IN_ORIGINAL" -> "引用不是对应工具结果中的连续原文";
            case "QUOTE_NOT_IN_INPUT" -> "引用未出现在本次摘要输入或可沿用的旧摘要中";
            case "STALE_EVIDENCE" -> "摘要引用了已过期的证据";
            case "NO_COMPRESSION_GAIN" -> "摘要长度没有小于被替换的上下文";
            case "SOURCE_OUTSIDE_WINDOW" -> "引用来源不在允许摘要的历史窗口内";
            case "INVALID_JSON_SYNTAX" -> "模型返回的摘要不是合法的完整JSON";
            case "INVALID_JSON_STRUCTURE" -> "摘要JSON结构或条目数量不符合要求";
            case "INVALID_FIELDS" -> "摘要或模型响应字段缺失或格式错误";
            case "INVALID_NOTE_KIND" -> "摘要条目类型不受支持";
            case "INVALID_NOTE_VALUES" -> "摘要条目字段为空或超过长度限制";
            case "NEXT_STEP_FORBIDDEN" -> "摘要包含禁止的NEXT_STEP条目";
            case "RESPONSE_TOO_LARGE", "SUMMARY_TOO_LARGE" -> "摘要超过字符长度上限";
            case "INVALID_SUMMARY" -> "摘要版本、覆盖范围或条目数量无效";
            case "INCOMPLETE_BATCH_COVERAGE" -> "摘要覆盖范围未包含完整工具调用批次";
            case "SUMMARY_BUDGET_EXCEEDED" -> "摘要超过预留的上下文预算";
            case "REQUEST_BUDGET_CHECK_FAILED" -> "候选摘要的完整请求预算检查失败";
            case "MODEL_OUTPUT_LIMIT" -> "摘要响应达到输出长度上限；需要调整输出预算或缩小输入";
            case "MODEL_INVALID_TOOL_ARGUMENTS" -> "模型工具参数格式不符合定义";
            case "MODEL_INVALID_JSON" -> "模型响应不是完整合法JSON";
            case "MODEL_INVALID_ENVELOPE", "MODEL_EMPTY_ANSWER", "MODEL_RESPONSE_INTERRUPTED" -> "模型响应结构无效、为空或被中断";
            case "MODEL_TRANSPORT" -> "模型请求网络传输失败";
            case "MODEL_TIMEOUT" -> "摘要模型请求超时";
            case "MODEL_INTERRUPTED" -> "摘要模型请求被中断";
            case "MODEL_HTTP_ERROR" -> "摘要模型返回HTTP错误；状态码可在模型调用指标中查看";
            case "MODEL_REQUEST_BUDGET_EXCEEDED" -> "摘要模型请求超过配置的完整请求预算";
            case "TRANSPORT_OR_ENVELOPE_JSON_ERROR" -> "模型通信或响应外层JSON解析失败";
            case "INVALID_RESPONSE_ENVELOPE" -> "模型响应不完整或外层结构不符合要求";
            default -> "未分类的异常；未记录原始异常文本";
        }, failure, call, from, to);
    }

    public String detail() {
        return "Summary rejected; retaining previous state; stage=" + stage + ", reasonCode=" + reasonCode
                + ", reason=" + reason + ", exceptionType=" + exceptionType + ", call=" + modelCallNumber
                + ", fromIndex=" + fromIndex + ", toIndex=" + toIndex
                + (modelDiagnostic == null ? "" : ", " + modelDiagnostic.detail());
    }
    public dev.backendagent.model.ModelFailureDiagnostic getModelDiagnostic() { return modelDiagnostic; }
    public Stage getStage() { return stage; }
    public String getReasonCode() { return reasonCode; }
    public String getReason() { return reason; }
    public String getExceptionType() { return exceptionType; }
    public int getModelCallNumber() { return modelCallNumber; }
    public int getFromIndex() { return fromIndex; }
    public int getToIndex() { return toIndex; }
}
