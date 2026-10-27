package dev.backendagent.runtime;

import dev.backendagent.selection.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.cli.AgentOptions;
import dev.backendagent.model.*;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolDiscoveryTest {
    private static final List<ToolDefinition> CATALOG = List.of(
            new ToolDefinition("read_file", "读取文件原文", Map.of("path", "文件路径")),
            new ToolDefinition("search_code", "搜索代码关键词", Map.of("query", "方法名称")),
            new ToolDefinition("run_tests", "执行 Maven 测试", Map.of()),
            new ToolDefinition("workspace_diff", "查看文件改动", Map.of()));
    private static ToolDiscovery hidden() { return new ToolDiscovery(CATALOG, (t,h,c,k) -> List.of(c.getFirst()), 1); }
    private static void remember(AgentSession session, ToolDiscovery discovery, String query) {
        var result = discovery.search(session, Map.of("query", query));
        session.remember(new ToolExchange(new ToolCall("s" + session.history().size(), "search_tools", Map.of("query", query)), result));
    }
    private static List<String> names(ToolDiscovery discovery, AgentSession session) {
        return discovery.definitions(session).stream().map(ToolDefinition::getName).toList();
    }
    @Test void onlyMatchingDefinitionsAreAddedAndResultDoesNotDuplicateSchemas() throws Exception {
        var session = new AgentSession("定位方法"); var discovery = hidden();
        assertEquals(List.of("read_file", "search_tools"), names(discovery, session));
        var result = discovery.search(session, Map.of("query", "search_code"));
        var data = new ObjectMapper().readTree(result.content());
        assertEquals("ADDED", data.path("status").asText());
        assertEquals(1, data.path("tools").size());
        assertFalse(result.content().contains("parameters"));
        remember(session, discovery, "search_code");
        assertEquals(List.of("read_file", "search_code", "search_tools"), names(discovery, session));
        assertEquals(names(discovery, session), names(hidden(), session)); // No transient state required on reopen.
    }
    @Test void widerQueryFindsToolAfterFirstMiss() {
        var session = new AgentSession("定位方法"); var discovery = hidden();
        var result = discovery.search(session, Map.of("query", "xyz_nonexistent_capability"));
        assertTrue(result.content().contains("NO_MATCH"));
        remember(session, discovery, "xyz_nonexistent_capability");
        assertEquals(List.of("read_file", "search_tools"), names(discovery, session));
        remember(session, discovery, "搜索代码");
        assertTrue(names(discovery, session).contains("search_code"));
        assertFalse(names(discovery, session).contains("run_tests"));
    }
    @Test void preciseMatchDoesNotPadResultsWithGenericWordMatches() throws Exception {
        var catalog = List.of(
                new ToolDefinition("search_code", "搜索代码关键词，返回文件内容", Map.of()),
                new ToolDefinition("create_file", "创建文件内容", Map.of()),
                new ToolDefinition("workspace_diff", "查看文件内容改动", Map.of()));
        var discovery = new ToolDiscovery(catalog, (t,h,c,k) -> List.of(), 0);
        var result = new ObjectMapper().readTree(discovery.search(new AgentSession("查找"),
                Map.of("query","搜索代码关键词 文件内容")).content());
        assertEquals(1, result.path("tools").size());
        assertEquals("search_code", result.path("tools").get(0).path("name").asText());
    }

    @Test void secondMissFallsBackAndRepeatedSearchCannotRehideTools() {
        var session = new AgentSession("定位方法"); var discovery = hidden();
        remember(session, discovery, "zzzz"); remember(session, discovery, "zzzz");
        assertEquals(CATALOG.size()+1, names(discovery, session).size());
        assertTrue(discovery.search(session, Map.of("query", "zzzz")).content().contains("FULL_FALLBACK"));
    }
    @Test void secondSearchAlsoMatchesParameterDescriptions() {
        var session = new AgentSession("定位方法"); var discovery = hidden();
        remember(session, discovery, "方法名称");
        assertFalse(names(discovery, session).contains("search_code"));
        remember(session, discovery, "方法名称");
        assertTrue(names(discovery, session).contains("search_code"));
    }
    @Test void alreadyVisibleAndInvalidQueryAreExplicit() {
        var session = new AgentSession("定位方法"); var discovery = hidden();
        assertTrue(discovery.search(session, Map.of("query", "read_file")).content().contains("ALREADY_VISIBLE"));
        assertFalse(discovery.search(session, Map.of("query", " ")).successful());
        assertFalse(discovery.search(session, Map.of("query", "x", "limit", "5")).successful());
        assertFalse(discovery.search(session, Map.of()).successful());
    }
    @Test void selectorFailureRestoresAuthorizedCatalogAndKeepsDiscovery() {
        var discovery = new ToolDiscovery(CATALOG, (t,h,c,k) -> { throw new IllegalStateException(); }, 8);
        assertEquals(5, names(discovery, new AgentSession("定位方法")).size());
        assertThrows(IllegalArgumentException.class, () -> new ToolDiscovery(List.of(ToolDiscovery.definition())));
    }
    @Test void searchIsNotAWriteAndDiscoveriesDoNotLeakAcrossSessionsOrTurns() {
        var discovery = hidden(); var session = new AgentSession("定位方法");
        remember(session, discovery, "search_code");
        assertEquals(0, session.workspaceState().getRevision());
        assertFalse(names(discovery, new AgentSession("其他任务")).contains("search_code"));
        session.transitionTo(AgentSession.Status.RUNNING); session.finish("完成");
        session.transitionTo(AgentSession.Status.COMPLETED); session.continueWith("新需求");
        assertFalse(names(discovery, session).contains("search_code"));
    }
    @Test void successfulTestsDoNotForceEmptyCatalog() {
        var history = List.of(new ToolExchange(new ToolCall("t", "run_tests", Map.of()), new ToolResult(true,"passed")));
        assertFalse(new RuleToolSelector().select("继续检查", history, CATALOG, 8).isEmpty());
    }
    @Test void executedCapabilitiesSurvivePhaseChangesAndResetOnNextTurn() {
        var session = new AgentSession("继续修改"); var discovery = hidden();
        session.remember(new ToolExchange(new ToolCall("used", "search_code", Map.of("query", "cancel")),
                new ToolResult(true, "found")));
        session.remember(new ToolExchange(new ToolCall("next", "read_file", Map.of("path", "Order.java")),
                new ToolResult(true, "class Order {}")));
        assertEquals(List.of("read_file", "search_code", "search_tools"), names(discovery, session));
        assertEquals(names(discovery, session), names(hidden(), session));
        assertTrue(discovery.search(session, Map.of("query", "search_code")).content().contains("ALREADY_VISIBLE"));
        session.transitionTo(AgentSession.Status.RUNNING); session.finish("完成");
        session.transitionTo(AgentSession.Status.COMPLETED); session.continueWith("下一轮");
        assertFalse(names(discovery, session).contains("search_code"));
    }
    @Test void failedOperationsKeepTheirCapabilityButHistoryCannotGrantUnregisteredTools() {
        var session = new AgentSession("修正参数后重试");
        session.remember(new ToolExchange(new ToolCall("failed", "search_code", Map.of("query", "")),
                new ToolResult(false, "query is empty")));
        session.remember(new ToolExchange(new ToolCall("unknown", "not_registered", Map.of()),
                new ToolResult(false, "unknown tool")));
        assertTrue(names(hidden(), session).contains("search_code"));
        assertFalse(names(hidden(), session).contains("not_registered"));
        assertFalse(names(hidden(), new AgentSession("其他会话")).contains("search_code"));
    }
    @Test void regressionTaskKeepsFileCreationAfterPatchingDespiteHistoryKeywordBoost() {
        var catalog = List.of("list_files", "read_file", "integer_calculate", "read_observation", "search_history",
                "search_code", "search_turns", "read_turn", "remember_fact", "apply_patch", "create_file",
                "workspace_diff", "run_tests", "apply_patch_batch").stream()
                .map(n -> new ToolDefinition(n, "能力 " + n, Map.<String,String>of())).toList();
        var history = List.of(new ToolExchange(new ToolCall("patched", "apply_patch_batch", Map.of()),
                new ToolResult(true, "changed")));
        String task = "修复AlertEvaluator和LogAnalyzer，历史故障输出不是期望值，补充混合日志回归并运行。";
        var selected = new RuleToolSelector().select(task, history, catalog, 8).stream().map(ToolDefinition::getName).toList();
        assertTrue(selected.contains("create_file"));
        assertTrue(selected.contains("run_tests"));
        assertEquals(8, selected.size());
    }
    @Test void cliSelectionIsExplicitAndValidated() {
        assertFalse(AgentOptions.parse(new String[]{"--workspace","repo","--task","x"}).isToolSelectionEnabled());
        assertTrue(AgentOptions.parse(new String[]{"--workspace","repo","--task","x","--ptc", "false", "--tool-selection","rule"}).isToolSelectionEnabled());
        assertThrows(IllegalArgumentException.class, () -> AgentOptions.parse(new String[]{"--workspace","repo","--task","x","--ptc", "false", "--tool-selection","unknown"}));
    }
    @Test void unavailableToolRecoveryIsDurableAndTurnLocal() {
        var session = new AgentSession("定位方法"); var discovery = hidden();
        session.append(AgentSession.EventType.MODEL_RETRY_SCHEDULED, discovery.recoveryEvent(session, List.of("search_code")));
        assertTrue(names(discovery, session).contains("search_code"));
        assertTrue(names(hidden(), session).contains("search_code"));
        assertFalse(names(hidden(), session).contains("run_tests"));
        assertTrue(discovery.definitions(session).getLast().getDescription().contains("整个批次未执行"));
        assertTrue(discovery.definitions(session).getLast().getDescription().contains("search_code"));
        assertTrue(discovery.definitions(session).getLast().getDescription().contains("现已提供完整参数定义"));
        session.transitionTo(AgentSession.Status.RUNNING); session.finish("完成");
        session.transitionTo(AgentSession.Status.COMPLETED); session.continueWith("下一轮");
        assertFalse(names(discovery, session).contains("search_code"));
    }
    @Test void nonexistentNameFallsBackWithoutPersistingUntrustedText() {
        var session = new AgentSession("定位方法"); var discovery = hidden();
        session.append(AgentSession.EventType.MODEL_RETRY_SCHEDULED, discovery.recoveryEvent(session, List.of("private-secret-unregistered-name")));
        assertEquals(5, names(discovery, session).size());
        assertFalse(session.events().toString().contains("private-secret-unregistered-name"));
        assertTrue(discovery.definitions(session).getLast().getDescription().contains("未注册工具"));
    }
    @Test void undeclaredToolFailureChangesNextRequestInsteadOfRetryingIdenticalContext() {
        var index = new AtomicInteger(); var executions = new AtomicInteger();
        Tool tool = new Tool() {
            public String name() { return "search_code"; }
            public ToolDefinition definition() { return CATALOG.get(1); }
            public ToolResult execute(Map<String,String> args) { executions.incrementAndGet(); return new ToolResult(true,"found"); }
        };
        ModelClient model = request -> switch(index.getAndIncrement()) {
            case 0 -> {
                assertEquals(1, request.availableTools().size());
                throw new UndeclaredToolCallFailure(List.of("search_code"), ModelFailureDiagnostic.FinishReason.TOOL_CALLS);
            }
            case 1 -> {
                assertEquals(0,executions.get()); assertEquals(0,request.history().size());
                assertTrue(request.availableTools().stream().anyMatch(t->t.getName().equals("search_code")));
                assertTrue(request.availableTools().getLast().getDescription().contains("整个批次未执行"));
                yield ModelResponse.callTool(new ToolCall("replanned","search_code",Map.of("query","cancel")));
            }
            default -> ModelResponse.finish("完成");
        };
        var session = new AgentSession("定位方法");
        new AgentRuntime(model,List.of(tool),4).withToolSelection((t,h,c,k)->List.of(),0).run(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status());
        assertEquals(1,executions.get()); assertEquals(1,session.history().size());
    }
    @Test void nonexistentToolRequestsAreBoundedByRetryAndCallBudget() {
        ModelClient model = request -> { throw new UndeclaredToolCallFailure(List.of("unknown"),ModelFailureDiagnostic.FinishReason.TOOL_CALLS); };
        var session = new AgentSession("定位方法");
        new AgentRuntime(model,List.of(),9).withToolSelection(true).run(session);
        assertEquals(AgentSession.Status.FAILED,session.status()); assertEquals(3,session.modelCalls());
        var shortSession = new AgentSession("定位方法");
        new AgentRuntime(model,List.of(),1).withToolSelection(true).run(shortSession);
        assertEquals(1,shortSession.modelCalls()); assertTrue(shortSession.history().isEmpty());
    }

    @Test void bm25UsesSameBoundedSearchAndFallbackWithoutChangingInitialSelector() {
        var session = new AgentSession("定位方法");
        var discovery = new ToolDiscovery(CATALOG,(t,h,c,k)->List.of(c.getFirst()),1,new Bm25ToolSearchRanker());
        assertEquals(List.of("read_file","search_tools"),names(discovery,session));
        remember(session,discovery,"quasar_unknown");
        assertFalse(names(discovery,session).contains("search_code"));
        remember(session,discovery,"方法名称");
        assertTrue(names(discovery,session).contains("search_code"));
        assertFalse(names(discovery,session).contains("run_tests"));
        var failed = new AgentSession("定位方法");
        remember(failed,discovery,"quasar_unknown"); remember(failed,discovery,"quasar_unknown");
        assertEquals(CATALOG.size()+1,names(discovery,failed).size());
    }

    @Test void runtimeExecutesDiscoveredToolOnNextRequest() {
        var calls = new AtomicInteger(); var executions = new AtomicInteger();
        Tool tool = new Tool() {
            public String name() { return "search_code"; }
            public ToolDefinition definition() { return CATALOG.get(1); }
            public ToolResult execute(Map<String,String> args) { executions.incrementAndGet(); return new ToolResult(true,"found cancellation"); }
        };
        ModelClient model = request -> switch(calls.getAndIncrement()) {
            case 0 -> {
                assertEquals(List.of("search_tools"), request.availableTools().stream().map(ToolDefinition::getName).toList());
                yield ModelResponse.callTool(new ToolCall("a","search_tools",Map.of("query","search_code")));
            }
            case 1 -> {
                assertEquals(0, executions.get());
                assertTrue(request.availableTools().stream().anyMatch(t->t.getName().equals("search_code")));
                yield ModelResponse.callTool(new ToolCall("b","search_code",Map.of("query","cancel")));
            }
            default -> ModelResponse.finish("定位完成");
        };
        var session = new AgentSession("定位方法");
        new AgentRuntime(model,List.of(tool),4).withToolSelection((t,h,c,k)->List.of(),0).run(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status());
        assertEquals(1,executions.get()); assertEquals(2,session.history().size());
    }

    @Test void catalogKeepsCoreAfterCalculationAndDirectoryListingWithoutTaskKeywordRules() {
        var catalog = List.of("list_files", "read_file", "search_code", "apply_patch", "run_tests",
                "integer_calculate", "search_history", "create_file").stream()
                .map(n -> new ToolDefinition(n, "能力 " + n, Map.<String,String>of())).toList();
        var discovery = new ToolDiscovery(catalog);
        var session = new AgentSession("改代码并回归；历史日志不用改");
        var initial = names(discovery, session);
        assertEquals(List.of("list_files", "read_file", "search_code", "apply_patch", "run_tests", "search_tools"), initial);
        String directory = discovery.definitions(session).getLast().getDescription();
        assertTrue(directory.contains("create_file: 创建不存在的新文件"));
        assertTrue(directory.contains("integer_calculate:"));
        assertFalse(directory.contains("expression"));
        assertFalse(directory.contains("apply_patch:"));
        session.remember(new ToolExchange(new ToolCall("calc", "integer_calculate", Map.of()), new ToolResult(true, "rate_bp=20")));
        session.remember(new ToolExchange(new ToolCall("list", "list_files", Map.of()), new ToolResult(true, "src/")));
        assertTrue(names(discovery, session).containsAll(initial));
        assertTrue(names(discovery, session).contains("integer_calculate"));
    }

    @Test void successfulDiscoveriesDoNotTriggerFullFallbackAndResetOnNewTurn() {
        var catalog = List.of("create_file", "integer_calculate", "workspace_diff", "read_turn").stream()
                .map(n -> new ToolDefinition(n, "optional " + n, Map.<String,String>of())).toList();
        var discovery = new ToolDiscovery(catalog);
        var session = new AgentSession("执行任务");
        for (String name : List.of("create_file", "integer_calculate", "workspace_diff")) remember(session, discovery, name);
        assertFalse(names(discovery, session).contains("read_turn"));
        assertFalse(discovery.definitions(session).getLast().getDescription().contains("create_file:"));
        assertTrue(discovery.definitions(session).getLast().getDescription().contains("read_turn:"));
        var reopened = new ToolDiscovery(catalog, new CoreToolSelector(), 8, (q,c,p) -> { throw new AssertionError("Must restore saved names, not rerank"); });
        assertEquals(names(discovery, session), names(reopened, session));
        session.transitionTo(AgentSession.Status.RUNNING); session.finish("完成");
        session.transitionTo(AgentSession.Status.COMPLETED); session.continueWith("新任务");
        assertEquals(List.of("search_tools"), names(discovery, session));
        assertEquals(List.of("search_tools"), names(discovery, new AgentSession("其他会话")));
    }

    @Test void exactDirectoryNameLoadsOnlyThatDefinitionWithoutFuzzySearch() {
        var discovery = new ToolDiscovery(CATALOG, new CoreToolSelector(), 8,
                (q,c,p) -> { throw new AssertionError("Exact name must bypass ranking"); });
        var session = new AgentSession("查看改动");
        remember(session, discovery, "workspace_diff");
        assertTrue(names(discovery, session).contains("workspace_diff"));
        assertEquals(5, names(discovery, session).size());
    }

    @Test void catalogRuntimeLoadsBeforeExecutingOptionalTool() {
        var step = new AtomicInteger(); var executions = new AtomicInteger();
        Tool calculator = new IntegerCalculateTool();
        Tool observed = new Tool() {
            public String name() { return calculator.name(); }
            public ToolDefinition definition() { return calculator.definition(); }
            public ToolResult execute(Map<String,String> args) { executions.incrementAndGet(); return calculator.execute(args); }
        };
        ModelClient model = request -> switch (step.getAndIncrement()) {
            case 0 -> {
                assertEquals(List.of("search_tools"), request.availableTools().stream().map(ToolDefinition::getName).toList());
                assertTrue(request.availableTools().getFirst().getDescription().contains("integer_calculate:"));
                yield ModelResponse.callTool(new ToolCall("load", "search_tools", Map.of("query", "integer_calculate")));
            }
            case 1 -> {
                assertEquals(0, executions.get());
                var definition = request.availableTools().stream().filter(t -> t.getName().equals("integer_calculate")).findFirst().orElseThrow();
                assertTrue(definition.getRequiredParameters().contains("result_name"));
                yield ModelResponse.callTool(new ToolCall("calc", "integer_calculate", Map.of("expression", "2*10000/10000", "result_name", "error_rate_bp")));
            }
            default -> {
                assertEquals("error_rate_bp=2", request.history().getLast().result().content());
                yield ModelResponse.finish("完成核算");
            }
        };
        var session = new AgentSession("核算错误率");
        new AgentRuntime(model, List.of(observed), 4).withToolSelection("catalog").run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(1, executions.get());
    }

    @Test void cliCatalogModeIsDistinctFromLegacyRuleMode() {
        var options = AgentOptions.parse(new String[]{"--workspace", "repo", "--task", "x", "--ptc", "false", "--tool-selection", "catalog"});
        assertEquals("catalog", options.getToolSelectionMode());
        assertTrue(options.isToolSelectionEnabled());
        assertEquals("rule", AgentOptions.parse(new String[]{"--workspace", "repo", "--task", "x", "--ptc", "false", "--tool-selection", "rule"}).getToolSelectionMode());
    }
}
