package dev.backendagent.model;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.backendagent.runtime.AgentRuntime;
import dev.backendagent.runtime.AgentSession;
import dev.backendagent.tools.ReadFileTool;
import dev.backendagent.tools.Tool;
import dev.backendagent.tools.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekModelClientTest {
    @Test void strictPtcConfigurationConstrainsOnlyTheGatewayAndKeepsBothFieldsRequired() throws Exception {
        var config = new dev.backendagent.config.ModelConfig("test-key", java.net.URI.create("http://127.0.0.1:1"),
                "test-model", Duration.ofSeconds(1), Duration.ofSeconds(1), new RequestBudget(65536,4096,1024), true);
        var adapter = new DeepSeekModelClient(config);
        var gateway = new dev.backendagent.ptc.PtcExecution((c,n,d) -> {throw new AssertionError();}, List.of(),
                (s,c,p) -> {throw new AssertionError();});
        var ptc = adapter.buildRequest(new ModelRequest("inspect", List.of(), List.of(gateway.definition()), 2));
        var function = ptc.path("tools").get(0).path("function");
        assertTrue(function.path("strict").asBoolean());
        var required = new java.util.HashSet<String>(); function.path("parameters").path("required").forEach(n -> required.add(n.asText()));
        assertEquals(java.util.Set.of("code","description"), required);
        var nativeBody = adapter.buildRequest(new ModelRequest("inspect", List.of(), List.of(new ReadFileTool(null).definition()), 2));
        assertFalse(nativeBody.path("tools").get(0).path("function").has("strict"));
    }
    @Test void ptcPromptKeepsDecisionGuidanceAndMovesExecutionContractToGateway() throws Exception {
        var session = new AgentSession("inspect");
        var tools = List.<Tool>of(new dev.backendagent.tools.ApplyPatchTool(null, session),
                new dev.backendagent.tools.ApplyPatchBatchTool(null, session),
                new dev.backendagent.tools.RememberFactTool(session), new ReadFileTool(null),
                new dev.backendagent.tools.IntegerCalculateTool());
        var gateway = new dev.backendagent.ptc.PtcExecution((code, names, dispatcher) -> {
            throw new AssertionError("Prompt inspection must not run programs");
        }, tools, (s, call, parent) -> { throw new AssertionError("No child calls"); });
        var body = client(Duration.ofSeconds(5)).buildRequest(new ModelRequest("inspect", List.of(), List.of(gateway.definition()), 2));
        String system = body.path("messages").get(0).path("content").asText();
        String sdk = body.path("tools").get(0).path("function").path("description").asText();
        assertTrue(system.contains("大整数、除法取整、单位换算"));
        assertTrue(system.contains("result_name标明业务量和单位"));
        assertTrue(system.contains("成功写入后必须测试当前源码快照"));
        assertTrue(system.contains("本轮用户要求优先"));
        assertTrue(system.contains("outputTruncated=true"));
        assertTrue(system.contains("archiveCallId"));
        assertFalse(system.contains("search_tools"));
        assertFalse(system.contains("连续同文件apply_patch由运行层归并"));
        assertFalse(system.contains("JSON解码后的文本"));
        assertFalse(system.contains("ToolCallError")); // Execution rules have one owner.
        assertTrue(sdk.contains("ToolCallError"));
        assertTrue(sdk.contains("不盲目重放整段程序"));
        assertTrue(sdk.contains("多个await apply_patch逐次提交，不自动归并或回滚"));
        assertTrue(sdk.contains("可在同一程序后续调用"));
        assertFalse(sdk.contains("下一轮再引用"));
        assertFalse(sdk.contains("同一响应中连续同文件补丁会整组校验"));
        assertTrue(sdk.contains("全部补丁按同一当前原文定位，先全量检查再原子写入"));
        assertTrue(sdk.contains("按missing/unexpected修正参数"));
        assertTrue(sdk.contains("code与description均必填"));
    }

    @Test void ptcSdkRetainsPerToolArgumentAndReturnDocumentation() throws Exception {
        var session = new AgentSession("inspect");
        var definitions = List.of(new ReadFileTool(null).definition(),
                new dev.backendagent.tools.WorkspaceDiffTool(null).definition(),
                new dev.backendagent.tools.SearchHistoryTool(new dev.backendagent.history.HistoryArchiveReader(
                        dev.backendagent.history.ObservationArchive.inMemory(session), null)).definition(),
                new dev.backendagent.tools.ApplyPatchBatchTool(null, session).definition(),
                new dev.backendagent.tools.IntegerCalculateTool().definition());
        String sdk = dev.backendagent.ptc.PtcSdk.render(definitions);
        for (var definition : definitions) {
            assertTrue(sdk.contains("async def " + definition.getName() + "("));
            for (var parameter : definition.getParameters().entrySet()) {
                assertTrue(sdk.contains("  " + parameter.getKey() + ": " + parameter.getValue()));
                assertTrue(sdk.contains(parameter.getKey() + ": str | int"
                        + (definition.getRequiredParameters().contains(parameter.getKey()) ? "" : " = ...")));
            }
        }
        assertEquals(definitions.size(), sdk.split("  返回: ", -1).length - 1);
        assertTrue(sdk.contains("patches需JSON字符串"));
        assertTrue(sdk.contains("nextBeforeSequence=0表示无下一页"));
        assertTrue(sdk.contains("value:十进制整数字符串"));
        assertTrue(sdk.contains("每次最多200行"));
        assertTrue(sdk.contains("不再次剥离"));
    }

    @Test void ptcChildBodiesAndArgumentsAreNotEncodedAsModelToolMessages() throws Exception {
        var child = new ToolExchange(new ToolCall("p:ptc:run:1","read_file",java.util.Map.of("path","PRIVATE_ARGUMENT")),
                new ToolResult(true,"PRIVATE_INTERMEDIATE_BODY"),null,1,null,null,"p");
        var outer = new ToolExchange(new ToolCall("p","run_code",java.util.Map.of("code","return 1","description","inspect")),
                new ToolResult(true,"FINAL_PROGRAM_RESULT"),null,1);
        var body = client(Duration.ofSeconds(5)).buildRequest(new ModelRequest("inspect",List.of(child,outer),
                List.of(new dev.backendagent.tools.ToolDefinition("run_code","program",java.util.Map.of("code","body","description","purpose"))),2));
        String encoded=body.toString();
        assertFalse(encoded.contains("PRIVATE_ARGUMENT"));assertFalse(encoded.contains("PRIVATE_INTERMEDIATE_BODY"));
        assertTrue(encoded.contains("FINAL_PROGRAM_RESULT"));assertTrue(encoded.contains("只能直接调用run_code"));
        assertEquals(1,body.path("tools").size());
    }

    @Test void currentTurnWritePathsRemainInExistingValidationMessageWithNoVisibleToolBodies() throws Exception {
        var writes = dev.backendagent.runtime.TurnWriteSummary.fromHistory(List.of(
                new ToolExchange(new ToolCall("write", "apply_patch", java.util.Map.of("path", "src/A.java")), new ToolResult(true, "written"))));
        var request = new ModelRequest("报告本轮修改", List.of(), List.of(), 2).withTurnWrites(writes);
        var body = client(Duration.ofSeconds(5)).buildRequest(request);
        var messages = body.path("messages");
        var validation = json.readTree(messages.get(2).path("content").asText());
        assertEquals("workspace_validation_state", validation.path("kind").asText());
        assertEquals("src/A.java", validation.path("currentTurnWrittenPaths").get(0).asText());
        assertTrue(validation.path("writeNotice").asText().contains("不等于最终差异或正确性"));
        assertEquals(3, messages.size());
    }
    @Test void discoveryInstructionOnlyAppearsWhenSearchIsAvailable() throws Exception {
        var adapter = client(Duration.ofSeconds(5));
        var full = adapter.buildRequest(new ModelRequest("inspect", List.of(), List.of(), 3));
        assertFalse(full.path("messages").get(0).path("content").asText().contains("待加载目录仅供发现"));
        var selected = adapter.buildRequest(new ModelRequest("inspect", List.of(),
                List.of(dev.backendagent.selection.ToolDiscovery.definition()), 3));
        assertTrue(selected.path("messages").get(0).path("content").asText().contains("待加载目录仅供发现，不是可调用定义"));
        assertTrue(selected.path("messages").get(0).path("content").asText().contains("新工具的参数定义在下一次模型请求中提供"));
        assertEquals(1, selected.path("tools").size());
        assertEquals("search_tools", selected.path("tools").get(0).path("function").path("name").asText());
    }

    @Test void undiscoveredToolIsRejectedUntilItsSchemaIsProvided() throws Exception {
        var adapter = client(Duration.ofSeconds(5));
        String response = toolResponse("{\"query\":\"cancel\"}").replace("read_file", "search_code");
        var hidden = new ModelRequest("inspect", List.of(), List.of(dev.backendagent.selection.ToolDiscovery.definition()), 3);
        responses.add(response);
        assertThrows(ModelCallFailure.class, () -> adapter.execute(hidden));
        var definition = new dev.backendagent.tools.ToolDefinition("search_code", "搜索代码", java.util.Map.of("query", "关键词"));
        var visible = new ModelRequest("inspect", List.of(), List.of(definition,dev.backendagent.selection.ToolDiscovery.definition()), 3);
        responses.add(response);
        assertEquals("search_code", adapter.execute(visible).getToolCalls().getFirst().name());
    }

    @Test void hiddenCallRejectsWholeBatchBeforeExecutingVisibleCalls() throws Exception {
        var adapter = client(Duration.ofSeconds(5));
        var response = (ObjectNode)json.readTree(toolResponse("{\"path\":\"a.java\",\"start_line\":1,\"end_line\":2}"));
        var calls = (com.fasterxml.jackson.databind.node.ArrayNode)response.path("choices").get(0).path("message").path("tool_calls");
        var hidden = calls.get(0).deepCopy();
        ((ObjectNode)hidden).put("id","hidden-call"); ((ObjectNode)hidden.path("function")).put("name","create_file");
        calls.add(hidden);
        responses.add(response.toString());
        var available = new dev.backendagent.tools.ToolDefinition("read_file","读取",java.util.Map.of("path","路径","start_line","起始","end_line","结束"));
        var failure = assertThrows(UndeclaredToolCallFailure.class, () -> adapter.execute(
                new ModelRequest("inspect",List.of(),List.of(available,dev.backendagent.selection.ToolDiscovery.definition()),3)));
        assertEquals(List.of("create_file"),failure.getProposedNames());
        assertEquals(ModelFailureDiagnostic.Code.UNDECLARED_TOOL,failure.getDiagnostic().getCode());
        assertFalse(json.writeValueAsString(failure.getDiagnostic()).contains("create_file"));
    }

    @Test void refinementSendsOnlyCandidateFactsAndRestoresTheSelectedSource() throws Exception {
        var note=new dev.backendagent.runtime.SummaryNote(dev.backendagent.runtime.SummaryNote.Kind.PROGRESS,
                "保留公共接口", "turn-1-user", "不修改公共接口",dev.backendagent.runtime.SummarySection.CONSTRAINTS);
        var input=new SummaryRefinementRequest(List.of(note),List.of(new SummaryEvidence("old-id","turn-1-user","不修改公共接口")),4288,4000);
        responses.add(finalResponse("{\"sections\":{\"CONSTRAINTS\":[{\"statement\":\"保留接口\",\"evidenceId\":\"E1\"}],\"FINDINGS\":[],\"CHANGES_AND_TESTS\":[],\"OPEN_ISSUES\":[]}}"));
        var notes=client(Duration.ofSeconds(5)).refineSummary(input);
        assertEquals("turn-1-user",notes.getFirst().getSourceCallId());
        assertEquals("不修改公共接口",notes.getFirst().getEvidenceQuote());
        var body=requests.getLast();assertFalse(body.has("tools"));
        var data=json.readTree(body.path("messages").get(1).path("content").asText());
        assertFalse(data.has("evidence"));assertFalse(data.toString().contains("不修改公共接口"));
        assertEquals("E1",data.path("sourceCosts").get(0).path("evidenceId").asText());
        assertTrue(data.path("sourceCosts").get(0).path("itemMetadataCharacters").asInt()>40);
        assertEquals(160,data.path("fixedMetadataReserve").asInt());
        assertEquals(1620,data.path("recommendedStatementCharacters").asInt());
        assertEquals(288,data.path("excessCharacters").asInt());
        assertEquals(3600,data.path("targetCharacters").asInt());assertFalse(data.has("objective"));
    }
    @Test void verificationEvidenceExpectationsAndRejectionReachTheRequest() throws Exception {
        var state=new dev.backendagent.verification.VerificationState();
        var basis=new dev.backendagent.verification.VerificationBasis("TAX",1,"read",new FileFingerprint("tax.md","1".repeat(64)),1,1,"tax=(base*rate+5000)/10000\n");
        state.observe(new ToolExchange(new ToolCall("pin","pin_verification_basis",java.util.Map.of("basis_id","TAX")),
                new ToolResult(true,dev.backendagent.verification.VerificationState.encode(basis))),1);
        state.refresh(java.util.Map.of("tax.md","1".repeat(64)),java.util.Set.of());
        var failure=dev.backendagent.verification.TestFailureReport.from("test",0,false,
                "testExecution=EXECUTED\ntestsExecuted=1\n[ERROR] TaxTest.amount:12 expected: <180> but was: <1800>");
        var view=new dev.backendagent.verification.VerificationContext(1,state,1,"test failed",failure);
        var request=new ModelRequest("repair",List.of(),List.of(),2).withVerificationContext(view).withNoProgressReport(null);
        JsonNode data=null,validation=null;
        for(var message:client(Duration.ofSeconds(5)).buildRequest(request).path("messages")){
            if(!message.path("role").asText().equals("user"))continue;
            var content=message.path("content").asText();if(!content.startsWith("{"))continue;
            var block=json.readTree(content);if(block.path("kind").asText().equals("current_turn_verification"))data=block.path("data");
            if(block.path("kind").asText().equals("workspace_validation_state"))validation=block;
        }
        assertNotNull(data);assertEquals("CURRENT",data.path("bases").get(0).path("validity").asText());
        assertFalse(data.has("expectations"));
        assertFalse(data.has("latestTest"));assertFalse(data.has("completionRejections"));
        assertNotNull(validation);assertEquals(1,validation.path("completionRejections").asInt());
        assertEquals("1800",validation.path("testDiagnostics").path("failures").get(0).path("actual").asText());
    }
    @Test void automaticBasisProtocolIncludesCurrentUserAndRequiresExactQuotesAndLaterUserReplacement() throws Exception {
        var old=new dev.backendagent.verification.VerificationSource("turn-4-user","USER",4,"仅统计5xx",null,"CURRENT");
        var current=new dev.backendagent.verification.VerificationSource("turn-5-user","USER",5,"统计4xx和5xx，排除429；429仍计入4xx桶",null,"CURRENT");
        var input=new VerificationUpdateRequest(5,List.of(old,current),List.of());
        responses.add(finalResponse("{\"bases\":[{\"source\":\"turn-5-user\",\"quote\":\"429仍计入4xx桶\",\"status\":\"AVAILABLE\",\"reason\":\"\"},{\"source\":\"turn-4-user\",\"quote\":\"仅统计5xx\",\"status\":\"SUPERSEDED\",\"reason\":\"用户已改变统计范围\",\"replacedBy\":\"turn-5-user\",\"replacementQuote\":\"统计4xx和5xx，排除429\"}]}"));
        var output=client(Duration.ofSeconds(5)).updateVerificationBasis(input);
        assertEquals(2,output.size());assertEquals("SUPERSEDED",output.get(1).status());
        var request=requests.getLast();assertFalse(request.has("tools"));
        var payload=json.readTree(request.path("messages").get(1).path("content").asText());
        assertEquals(5,payload.path("currentTask").path("turn").asInt());assertEquals(1,payload.path("history").size());
        assertEquals(4,payload.size());assertTrue(payload.has("previousBasis"));assertTrue(payload.has("contracts"));
        assertFalse(payload.toString().contains("currency"));assertFalse(payload.toString().contains("sha256"));
        responses.add(finalResponse("{\"bases\":[{\"source\":\"turn-5-user\",\"quote\":\"429不计入4xx桶\",\"status\":\"AVAILABLE\",\"reason\":\"\"}]}"));
        assertThrows(IllegalArgumentException.class,()->client(Duration.ofSeconds(5)).updateVerificationBasis(input));
    }
    @Test void previousBasisCanBeReturnedUnchangedWithoutLeakingSourceMetadata() throws Exception {
        var old=new dev.backendagent.verification.VerificationSource("turn-1-user","USER",1,"仅统计5xx",null,"CURRENT");
        var replacement=new dev.backendagent.verification.VerificationSource("turn-2-user","USER",2,"统计4xx和5xx，排除429",null,"CURRENT");
        var current=new dev.backendagent.verification.VerificationSource("turn-3-user","USER",3,"审计历史规则",null,"CURRENT");
        var doc=new dev.backendagent.verification.VerificationSource("read-doc","FILE",1,"429仍计入4xx桶",new FileFingerprint("docs/policy.md","1".repeat(64)),"CURRENT");
        var previous=List.of(
                new dev.backendagent.verification.ActiveVerificationBasis(doc,doc.content(),"AVAILABLE","",null,null),
                new dev.backendagent.verification.ActiveVerificationBasis(old,old.content(),"SUPERSEDED","",replacement,replacement.content()));
        var input=new VerificationUpdateRequest(3,List.of(current,doc),previous);
        var data=input.referenceData();
        var allowed=java.util.Set.of("source","quote","status","reason","replacedBy","replacementQuote");
        for(var item:data.path("previousBasis"))item.fieldNames().forEachRemaining(name->assertTrue(allowed.contains(name),name));
        assertEquals("docs/policy.md",data.path("contracts").get(0).path("path").asText());
        assertEquals("turn-2-user",data.path("history").get(0).path("source").asText());
        assertEquals(replacement.content(),data.path("previousBasis").get(1).path("replacementQuote").asText());
        var echoed=json.createObjectNode();echoed.set("bases",data.path("previousBasis"));
        responses.add(finalResponse(echoed.toString()));
        assertEquals(previous,client(Duration.ofSeconds(5)).updateVerificationBasis(input));
        var prompt=requests.getLast().path("messages").get(0).path("content").asText();
        assertTrue(prompt.contains("返回完整更新列表"));
    }
    @Test void systemPromptExplainsBasisRolesAndKeepsExecutionDiagnosticsSeparate() throws Exception {
        var body=client(Duration.ofSeconds(5)).buildRequest(new ModelRequest("repair",List.of(),List.of(),2));
        String prompt=body.path("messages").get(0).path("content").asText();
        assertTrue(prompt.contains("每轮自动更新"));assertTrue(prompt.contains("SUPERSEDED/STALE只作历史提醒"));
        assertTrue(prompt.contains("workspace_validation_state保存当前测试状态及诊断"));
        assertTrue(prompt.contains("实现、测试源码及其注释和断言是待核查对象"));
        assertTrue(prompt.contains("历史样例的数值只适用于当时输入"));
        assertTrue(prompt.contains("修改已通过用例应有需求变更或明确契约理由"));
        var validation=json.readTree(java.util.stream.StreamSupport.stream(body.path("messages").spliterator(),false)
                .map(m->m.path("content").asText()).filter(t->t.startsWith("{\"kind\":\"workspace_validation_state\""))
                .findFirst().orElseThrow());
        assertTrue(validation.path("notice").asText().contains("expected来自现有断言，actual来自实现"));
    }
    @Test void ordinaryVerificationBasisDoesNotRequireExplanatoryReason() throws Exception {
        var source=new dev.backendagent.verification.VerificationSource("turn-1-user","USER",1,"429仍计入4xx桶",null,"CURRENT");
        responses.add(finalResponse("{\"bases\":[{\"source\":\"turn-1-user\",\"quote\":\"429仍计入4xx桶\",\"status\":\"AVAILABLE\"}]}"));
        var bases=client(Duration.ofSeconds(5)).updateVerificationBasis(new VerificationUpdateRequest(1,List.of(source),List.of()));
        assertEquals("",bases.getFirst().reason());
        var data=json.readTree(requests.getLast().path("messages").get(1).path("content").asText());
        assertEquals(4,data.size());assertFalse(data.toString().contains("CURRENT"));
    }
    @Test void verificationProtocolAcceptsMoreThanTwelveAndCompactsWithSameSchema() throws Exception {
        var source=new dev.backendagent.verification.VerificationSource("turn-1-user","USER",1,"abcdefghijklmnopqrstuvwxyz"+"x".repeat(600),null,"CURRENT");
        var input=new VerificationUpdateRequest(1,List.of(source),List.of());
        var output=json.createObjectNode();var entries=output.putArray("bases");
        for(char c='a';c<='z';c++)entries.addObject().put("source",source.id()).put("quote",String.valueOf(c)).put("status","AVAILABLE");
        responses.add(finalResponse(output.toString()));
        assertEquals(26,input.validate(client(Duration.ofSeconds(5)).updateVerificationBasis(input)).size());
        var basis=new dev.backendagent.verification.ActiveVerificationBasis(source,source.content(),"AVAILABLE","",null,null);
        var compact=new VerificationCompactionRequest(input,java.util.Collections.nCopies(12,basis),List.of(source));
        responses.add(finalResponse("{\"bases\":[{\"source\":\"turn-1-user\",\"quote\":\"abc\",\"status\":\"AVAILABLE\"}]}"));
        assertEquals("abc",client(Duration.ofSeconds(5)).compactVerificationBasis(compact).getFirst().quote());
        var wire=requests.getLast();assertFalse(wire.has("tools"));
        var prompt=wire.path("messages").get(0).path("content").asText();
        assertTrue(prompt.contains("不设置条数目标或条数上限"));assertFalse(prompt.contains("最多12条"));
        var data=json.readTree(wire.path("messages").get(1).path("content").asText());
        assertEquals(3999,data.path("maxOutputCharacters").asInt());
    }
    @Test void summarizedAnswerDoesNotReturnWhenItsOldToolsRemainVisible() throws Exception {
        var old=new dev.backendagent.runtime.ConversationTurn(1,"旧需求",AgentSession.Status.COMPLETED,"OLD_ANSWER_SHOULD_NOT_REAPPEAR",0,1);
        var current=new dev.backendagent.runtime.ConversationTurn(2,"当前需求",AgentSession.Status.RUNNING,null,1,1);
        var summary=new dev.backendagent.runtime.ConversationSummary(1,1,List.of(new dev.backendagent.runtime.SummaryNote(dev.backendagent.runtime.SummaryNote.Kind.PROGRESS,"旧轮需求","turn-1-user","旧需求")));
        var read=new ToolExchange(new ToolCall("old-visible-tool","read_file",java.util.Map.of("path","A.java")),new ToolResult(true,"class A {}"),null,1);
        var request=new ModelRequest("当前需求",List.of(read),List.of(),5,0,0,List.of(),null,null,java.util.Set.of(),dev.backendagent.runtime.WorkspaceState.initial(),List.of(),List.of(old,current),summary,2);
        var body=client(Duration.ofSeconds(5)).buildRequest(request).toString();
        assertTrue(body.contains("old-visible-tool"));assertFalse(body.contains("OLD_ANSWER_SHOULD_NOT_REAPPEAR"));
        assertFalse(dev.backendagent.verification.VerificationSources.visible(request).stream().anyMatch(x->x.id().equals("turn-1-answer")));
    }
    @Test void numberedIsTheOnlyModelFacingReadFormat() throws Exception {
        responses.add(toolResponse("{\"path\":\"OrderService.java\",\"start_line\":1,\"end_line\":20}"));
        responses.add(finalResponse("done"));
        var session=new AgentSession("inspect numbered");
        new AgentRuntime(client(Duration.ofSeconds(5)),List.of(readTool),3).run(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status());
        assertEquals("file=OrderService.java, totalLines=1\n1: "+Files.readString(repository.resolve("OrderService.java")).stripTrailing()+"\n",session.history().getFirst().result().content());
        assertEquals("numbered",session.history().getFirst().result().readMetadata().getFormat());
        var schema=requests.getFirst().path("tools").get(0).path("function").path("parameters");
        assertFalse(schema.path("properties").has("format"));
        assertFalse(schema.path("required").toString().contains("format"));
        var toolMessage=java.util.stream.StreamSupport.stream(requests.get(1).path("messages").spliterator(),false)
                .filter(m->m.path("role").asText().equals("tool")).findFirst().orElseThrow();
        var returned=json.readTree(toolMessage.path("content").asText());
        assertTrue(returned.path("readMetadata").path("reachedEndOfFile").asBoolean());
        assertEquals(1,returned.path("readMetadata").path("totalLines").asInt());
    }
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentLinkedQueue<String> responses = new ConcurrentLinkedQueue<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
    private final AtomicInteger responseDelayMillis = new AtomicInteger();
    @TempDir
    Path repository;
    private Tool readTool;
    private HttpServer server;
    private URI baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        Files.writeString(repository.resolve("OrderService.java"), "class OrderService { void cancel() { cache.evict(id); } }");
        readTool = new ReadFileTool(new Workspace(repository, repository.resolve("agent-local.properties")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                requests.add(json.readTree(exchange.getRequestBody()));
                if (responseDelayMillis.get() > 0) {
                    try {
                        Thread.sleep(responseDelayMillis.get());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                String body = responses.poll();
                if (body == null) {
                    body = "{}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                Integer nextStatus = statuses.poll();
                exchange.sendResponseHeaders(nextStatus == null ? status.get() : nextStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void invalidToolArgumentsRetryBeforeAnyToolRunsAndKeepIdenticalRequests() {
        responses.add(toolResponse("{\"path\":42}"));
        responses.add(toolResponse("{\"path\":\"OrderService.java\",\"start_line\":1,\"end_line\":20}"));
        responses.add(finalResponse("done"));
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(3, session.modelCalls());
        assertEquals(1, session.history().size());
        assertEquals(requests.get(0), requests.get(1));
        var failure = session.events().stream().filter(e -> e.type() == AgentSession.EventType.MODEL_CALL_FAILED).findFirst().orElseThrow();
        assertTrue(failure.detail().contains("INVALID_TOOL_ARGUMENTS"));
        assertEquals(1, session.events().stream().filter(e -> e.type() == AgentSession.EventType.TOOL_CALL_REQUESTED).count());
    }

    @Test
    void missingPtcDescriptionReturnsToolFeedbackAndOnlyCorrectedProgramExecutes() throws Exception {
        for(int n=0;n<2;n++) {
            var response=(ObjectNode)json.readTree(toolResponse(n==0?"{\"code\":\"return 1\"}":"{\"code\":\"return 1\",\"description\":\"inspect\"}"));
            var call=(ObjectNode)response.path("choices").get(0).path("message").path("tool_calls").get(0);
            call.put("id","program-"+n);((ObjectNode)call.path("function")).put("name","run_code");responses.add(response.toString());
        }
        responses.add(finalResponse("done"));
        var runs=new AtomicInteger();var session=new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)),List.of(),4).withPtc((c,n,d)->{
            runs.incrementAndGet();return new dev.backendagent.ptc.PtcRunResult(json.createObjectNode().put("done",true),List.of(),null);
        }).run(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(1,runs.get());
        assertEquals(3,requests.size());assertNotEquals(requests.get(0),requests.get(1));
        assertTrue(requests.get(1).toString().contains("missing=[description]"));
        assertFalse(session.history().getFirst().result().successful());assertTrue(session.history().getLast().result().successful());
        assertTrue(session.events().stream().noneMatch(e->e.type()==AgentSession.EventType.MODEL_CALL_FAILED));
    }

    @Test
    void ptcMalformedJsonAndNonStringCodeStillFailProtocolValidation() throws Exception {
        var definition=new dev.backendagent.tools.ToolDefinition("run_code","program",java.util.Map.of("code","body","description","purpose"));
        for(String arguments:List.of("{bad-json","{\"code\":12,\"description\":\"inspect\"}")) {
            var response=(ObjectNode)json.readTree(toolResponse(arguments));
            ((ObjectNode)response.path("choices").get(0).path("message").path("tool_calls").get(0).path("function")).put("name","run_code");
            responses.add(response.toString());
            var error=assertThrows(ModelCallFailure.class,()->client(Duration.ofSeconds(5)).execute(
                    new ModelRequest("inspect",List.of(),List.of(definition),1)));
            assertEquals(ModelFailureDiagnostic.Code.INVALID_TOOL_ARGUMENTS,error.getDiagnostic().getCode());
        }
    }

    @Test
    void transientHttpErrorRetriesButDoesNotPersistErrorBody() {
        statuses.add(503); statuses.add(200);
        responses.add("server echoed test-key and private content"); responses.add(finalResponse("done"));
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(2, session.modelCalls());
        assertTrue(session.events().stream().anyMatch(e -> e.detail().contains("httpStatus=503")));
        assertFalse(session.events().toString().contains("test-key"));
        assertFalse(session.events().toString().contains("private content"));
    }

    @Test
    void repeatedErrorsStopAfterThreeAttempts() {
        status.set(429);
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 10).run(session);
        assertEquals(AgentSession.Status.FAILED, session.status());
        assertEquals(3, session.modelCalls());
        assertEquals(3, requests.size());
        assertEquals(2, session.events().stream().filter(e -> e.type() == AgentSession.EventType.MODEL_RETRY_SCHEDULED).count());
        assertTrue(session.failure().canResume());
    }

    @Test
    void retryCannotExceedSessionBudget() {
        status.set(503);
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 2).run(session);
        assertEquals(AgentSession.Status.FAILED, session.status());
        assertEquals(2, session.modelCalls());
        assertEquals(2, requests.size());
    }

    @Test
    void lengthIsDiagnosedWithoutReplayingPartialToolCalls() throws Exception {
        var root = (ObjectNode) json.readTree(toolResponse("{\"path\":\"OrderService.java\",\"start_line\":1,\"end_line\":20}"));
        ((ObjectNode) root.path("choices").get(0)).put("finish_reason", "length");
        responses.add(root.toString());
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 10).run(session);
        assertEquals(1, session.modelCalls());
        assertTrue(session.history().isEmpty());
        assertTrue(session.events().stream().anyMatch(e -> e.detail().contains("code=OUTPUT_LIMIT, finishReason=LENGTH")));
        assertFalse(session.events().stream().anyMatch(e -> e.type() == AgentSession.EventType.TOOL_CALL_REQUESTED));
    }

    @Test
    void unknownFinishReasonIsRedactedAndNotRetried() throws Exception {
        var root = (ObjectNode) json.readTree(finalResponse("private content"));
        ((ObjectNode) root.path("choices").get(0)).put("finish_reason", "server echoed test-key");
        responses.add(root.toString());
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 10).run(session);
        assertEquals(1, session.modelCalls());
        assertTrue(session.events().stream().anyMatch(e -> e.detail().contains("finishReason=OTHER")));
        assertFalse(session.events().toString().contains("test-key"));
        assertFalse(session.events().toString().contains("private content"));
    }

    @Test
    void truncatedArgumentsHaveBoundedRedactedDiagnostics() throws Exception {
        String partial = "{\"path\":\"test-key-private.java\",\"content\":\"" + "private-value".repeat(100);
        var root = (ObjectNode) json.readTree(toolResponse(partial));
        ((ObjectNode) root.path("choices").get(0)).put("finish_reason", "length");
        responses.add(root.toString());
        var request = new ModelRequest("inspect", List.of(), List.of(readTool.definition()), 1);
        var failure = assertThrows(ModelCallFailure.class, () -> client(Duration.ofSeconds(5)).execute(request));
        var detail = failure.getDiagnostic().getOutputLimit();
        assertEquals(4096, detail.getMaxOutputTokens());
        assertEquals(1, detail.getToolCallCount());
        var tool = detail.getTools().getFirst();
        assertEquals("read_file", tool.getName());
        assertEquals(partial.length(), tool.getArgumentCharacters());
        assertFalse(tool.isArgumentsValidJson());
        assertTrue(tool.getHeadShape().length() <= 96);
        assertTrue(tool.getTailShape().length() <= 96);
        String stored = json.writeValueAsString(failure.getDiagnostic());
        assertFalse(stored.contains("test-key"));
        assertFalse(stored.contains("private-value"));
        assertEquals(1, requests.size());
    }

    @Test
    void textTruncationIsDistinguishedFromToolArguments() throws Exception {
        var root = (ObjectNode) json.readTree(finalResponse("partial private answer"));
        ((ObjectNode) root.path("choices").get(0)).put("finish_reason", "length");
        responses.add(root.toString());
        var request = new ModelRequest("inspect", List.of(), List.of(readTool.definition()), 1);
        var failure = assertThrows(ModelCallFailure.class, () -> client(Duration.ofSeconds(5)).execute(request));
        var detail = failure.getDiagnostic().getOutputLimit();
        assertEquals(22, detail.getContentCharacters());
        assertEquals(0, detail.getToolCallCount());
        assertTrue(detail.getTools().isEmpty());
        assertFalse(json.writeValueAsString(failure.getDiagnostic()).contains("private answer"));
    }

    @Test
    void requestsReserveLargerOutputAndAskForSmallerWrites() throws Exception {
        responses.add(finalResponse("done"));
        client(Duration.ofSeconds(5)).execute(new ModelRequest("inspect", List.of(), List.of(readTool.definition()), 1));
        assertEquals(4096, requests.getFirst().path("max_tokens").asInt());
        String prompt = requests.getFirst().path("messages").get(0).path("content").asText();
        assertTrue(prompt.contains("新建大文件一次只处理一个"));
        assertTrue(prompt.contains("已有文件整文件重写也用apply_patch"));
        assertTrue(prompt.contains("old_text为完整旧文件，new_text为完整新文件"));
    }

    @Test
    void invalidEnvelopeJsonCanRecoverOnNextAttempt() {
        responses.add("{bad-json private content"); responses.add(finalResponse("done"));
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(2, session.modelCalls());
        assertTrue(session.events().stream().anyMatch(e -> e.detail().contains("code=INVALID_JSON")));
        assertFalse(session.events().toString().contains("private content"));
    }

    @Test
    void trailingToolArgumentJsonIsRejectedBeforeExecution() {
        responses.add(toolResponse("{\"path\":\"OrderService.java\",\"start_line\":1,\"end_line\":20} {}"));
        var failure = assertThrows(ModelCallFailure.class, () -> client(Duration.ofSeconds(5)).execute(request()));
        assertEquals(ModelFailureDiagnostic.Code.INVALID_TOOL_ARGUMENTS, failure.getDiagnostic().getCode());
    }

    @Test
    void runtimeSendsPlaceholderAfterPatchAndLeavesOriginalReadInHistory() throws Exception {
        responses.add(toolResponse("{\"path\":\"OrderService.java\",\"start_line\":\"1\",\"end_line\":\"20\"}"));
        var patch = (ObjectNode) json.readTree(toolResponse("{}"));
        var call = (ObjectNode) patch.path("choices").get(0).path("message").path("tool_calls").get(0);
        call.put("id", "patch");
        ((ObjectNode) call.path("function")).put("name", "apply_patch").put("arguments", json.writeValueAsString(
                java.util.Map.of("path", "OrderService.java", "old_text", "cache.evict(id)", "new_text", "cache.evict(cancelledId)")));
        responses.add(patch.toString());
        responses.add(finalResponse("changed; tests needed"));
        var workspace = new Workspace(repository, repository.resolve("agent-local.properties"));
        var session = new AgentSession("modify cancellation");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(new ReadFileTool(workspace),
                new dev.backendagent.tools.ApplyPatchTool(workspace, session)), 3).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        var messages = requests.get(2).path("messages");
        JsonNode readResult = null;
        JsonNode patchResult = null;
        for (var message : messages) {
            if (message.path("role").asText().equals("tool")) {
                if (message.path("tool_call_id").asText().equals("call-1")) { readResult = json.readTree(message.path("content").asText()); }
                if (message.path("tool_call_id").asText().equals("patch")) { patchResult = json.readTree(message.path("content").asText()); }
            }
        }
        assertNotNull(readResult);
        assertNotNull(patchResult);
        assertTrue(readResult.path("content").asText().contains("STALE_OBSERVATION_BODY_OMITTED"));
        assertFalse(readResult.path("content").asText().contains("cache.evict(id)"));
        assertEquals("call-1", readResult.path("evidence").path("sourceCallId").asText());
        assertTrue(patchResult.path("successful").asBoolean());
        assertTrue(session.history().getFirst().result().content().contains("cache.evict(id)"));
        assertEquals(1, session.contextProjection().getFilteredExchanges());
    }

    @Test
    void sendsValidationStateEvenWhenOldPassingTestWasRemovedFromHistory() throws Exception {
        responses.add(finalResponse("needs testing"));
        var state = new dev.backendagent.runtime.WorkspaceState(3,
                new dev.backendagent.runtime.TestRunEvidence("old-pass", 2, true));
        var request = new ModelRequest("inspect", List.of(), List.of(readTool.definition()), 2,
                4, 0, List.of(), null, null, java.util.Set.of(), state);
        var client = client(Duration.ofSeconds(5));
        var report = client.inspectRequest(request);
        client.execute(request);
        var body = requests.getFirst();
        var validation = json.readTree(body.path("messages").get(2).path("content").asText());
        assertEquals("workspace_validation_state", validation.path("kind").asText());
        assertEquals(3, validation.path("workspaceRevision").asLong());
        assertEquals("STALE", validation.path("testStatus").asText());
        assertEquals("old-pass", validation.path("latestTest").path("callId").asText());
        assertEquals(2, validation.path("latestTest").path("workspaceRevision").asLong());
        assertEquals(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8).length, report.getRequestBytes());
    }

    @Test
    void runtimeCompletesTwoTurnsWithSchemaAndPairedHistory() throws IOException {
        responses.add(toolResponse("{\"path\":\"OrderService.java\",\"start_line\":\"1\",\"end_line\":\"20\"}"));
        responses.add(finalResponse("代码依次取消订单并删除缓存。"));
        var session = new AgentSession("read repository code");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals("代码依次取消订单并删除缓存。", session.answer());
        assertEquals(2, requests.size());
        assertEquals("Bearer test-key", authorization.get());
        JsonNode first = requests.getFirst();
        assertEquals("test-model", first.path("model").asText());
        assertEquals("disabled", first.path("thinking").path("type").asText());
        assertFalse(first.path("stream").asBoolean());
        assertEquals("required", first.path("tool_choice").asText());
        JsonNode function = first.path("tools").get(0).path("function");
        assertEquals("read_file", function.path("name").asText());
        assertEquals("string", function.path("parameters").path("properties").path("path").path("type").asText());
        assertEquals(3, function.path("parameters").path("required").size());
        assertTrue(function.path("parameters").path("required").toString().contains("path"));
        assertFalse(function.path("parameters").path("additionalProperties").asBoolean());

        JsonNode second = requests.get(1);
        assertEquals("auto", second.path("tool_choice").asText());
        JsonNode messages = second.path("messages");
        assertEquals(5, messages.size());
        assertEquals("user", messages.get(1).path("role").asText());
        assertEquals("read repository code", messages.get(2).path("content").asText());
        assertEquals("assistant", messages.get(3).path("role").asText());
        assertEquals("我先读取代码。", messages.get(3).path("content").asText());
        JsonNode replayedCall = messages.get(3).path("tool_calls").get(0);
        assertEquals("call-1", replayedCall.path("id").asText());
        JsonNode replayedArguments = json.readTree(replayedCall.path("function").path("arguments").asText());
        assertEquals("OrderService.java", replayedArguments.path("path").asText());
        assertEquals("tool", messages.get(4).path("role").asText());
        assertEquals("call-1", messages.get(4).path("tool_call_id").asText());
        JsonNode result = json.readTree(messages.get(4).path("content").asText());
        assertTrue(result.path("successful").asBoolean());
        assertTrue(result.path("content").asText().contains("cache.evict(id)"));
        assertEquals("call-1", result.path("evidence").path("sourceCallId").asText());
        assertEquals("FILE_CONTENT", result.path("evidence").path("type").asText());
        assertEquals(0, result.path("evidence").path("workspaceRevision").asLong());
        assertEquals("OrderService.java", result.path("evidence").path("file").path("path").asText());
        assertEquals(64, result.path("evidence").path("file").path("sha256").asText().length());
    }

    @Test
    void failedToolResultIsIncludedInNextRequest() throws IOException {
        responses.add(toolResponse("{\"path\":\"Missing.java\",\"start_line\":\"1\",\"end_line\":\"20\"}"));
        responses.add(finalResponse("文件不存在，无法分析。"));
        var session = new AgentSession("read missing repository file");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        JsonNode result = json.readTree(requests.get(1).path("messages").get(4).path("content").asText());
        assertFalse(result.path("successful").asBoolean());
        assertTrue(result.path("content").asText().contains("文件不可读取"));
        assertEquals(AgentSession.Status.COMPLETED, session.status());
    }

    @Test
    void httpErrorDoesNotLeakResponseBodyIntoSessionTrace() {
        status.set(401);
        responses.add("{\"error\":\"server echoed sensitive-dummy-key\"}");
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        assertEquals(AgentSession.Status.FAILED, session.status());
        assertEquals(1, requests.size());
        assertTrue(session.events().getLast().detail().contains("HTTP 401"));
        assertFalse(session.events().toString().contains("sensitive-dummy-key"));
        assertFalse(session.events().toString().contains("test-key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter", "aborted", "insufficient_system_resource"})
    void incompleteResponsesCannotCompleteSession(String finishReason) throws IOException {
        ObjectNode root = (ObjectNode) json.readTree(finalResponse("partial answer"));
        ((ObjectNode) root.path("choices").get(0)).put("finish_reason", finishReason);
        responses.add(root.toString());
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        assertEquals(AgentSession.Status.FAILED, session.status());
        assertNull(session.answer());
        assertTrue(session.history().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"path\":42}", "{\"path\":\"OrderService.java\",\"extra\":\"x\"}", "not-json"})
    void malformedArgumentsAreRejectedBeforeToolExecution(String arguments) throws IOException {
        responses.add(toolResponse(arguments));
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        assertEquals(AgentSession.Status.FAILED, session.status());
        assertTrue(session.history().isEmpty());
        assertEquals(3, requests.size());
    }

    @Test
    void integerLineArgumentsAreNormalizedAndReplayedWithoutChangingToolMap() throws IOException {
        responses.add(toolResponse("{\"path\":\"OrderService.java\",\"start_line\":1,\"end_line\":20}"));
        responses.add(finalResponse("done"));
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 2).run(session);

        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertTrue(session.history().getFirst().result().successful());
        assertEquals("1", session.history().getFirst().call().arguments().get("start_line"));
        var properties = requests.getFirst().path("tools").get(0).path("function")
                .path("parameters").path("properties");
        assertEquals("integer", properties.path("start_line").path("type").asText());
        assertEquals("string", properties.path("path").path("type").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "true", "null", "{}", "[]"})
    void nonIntegerLineArgumentsCannotExecuteTools(String value) throws IOException {
        responses.add(toolResponse("{\"path\":\"OrderService.java\",\"start_line\":" + value + ",\"end_line\":20}"));
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 2).run(session);
        assertEquals(AgentSession.Status.FAILED, session.status());
        assertTrue(session.history().isEmpty());
    }

    @Test
    void historyCursorAndLimitAcceptIntegerJsonValues() throws IOException {
        var response = (ObjectNode) json.readTree(toolResponse("{}"));
        var function = (ObjectNode) response.path("choices").get(0).path("message")
                .path("tool_calls").get(0).path("function");
        function.put("name", "search_turns").put("arguments",
                "{\"keyword\":\"inspect\",\"role\":\"user\",\"before_turn\":0,\"limit\":5}");
        responses.add(response.toString());
        responses.add(finalResponse("done"));
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)),
                List.of(new dev.backendagent.tools.SearchTurnsTool(session)), 2).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertTrue(session.history().getFirst().result().successful());
        assertEquals("5", session.history().getFirst().call().arguments().get("limit"));
    }

    @Test
    void largeIntegerArgumentsReachRangeValidationWithoutOverflow() throws IOException {
        responses.add(toolResponse("{\"path\":\"OrderService.java\",\"start_line\":9223372036854775808,\"end_line\":20}"));
        var result = client(Duration.ofSeconds(5)).execute(request());
        assertEquals("9223372036854775808", result.getToolCalls().getFirst().arguments().get("start_line"));
        assertFalse(readTool.execute(result.getToolCalls().getFirst().arguments()).successful());
    }

    @Test
    void multipleBatchesPreserveAssistantMessagesAndAllResults() throws IOException {
        responses.add(batchResponse("call-1", "call-2", "Missing.java"));
        responses.add(batchResponse("call-3", "call-4", "OrderService.java"));
        responses.add(finalResponse("分析完成。"));
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 3).run(session);

        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(3, session.modelCalls());
        assertEquals(4, session.history().size());
        JsonNode messages = requests.get(2).path("messages");
        assertEquals(9, messages.size());
        for (int batch = 0; batch < 2; batch++) {
            int offset = 3 + batch * 3;
            assertEquals("assistant", messages.get(offset).path("role").asText());
            assertEquals("我先读取代码。", messages.get(offset).path("content").asText());
            assertEquals(2, messages.get(offset).path("tool_calls").size());
            for (int index = 0; index < 2; index++) {
                String id = "call-" + (batch * 2 + index + 1);
                assertEquals(id, messages.get(offset).path("tool_calls").get(index).path("id").asText());
                JsonNode resultMessage = messages.get(offset + index + 1);
                assertEquals("tool", resultMessage.path("role").asText());
                assertEquals(id, resultMessage.path("tool_call_id").asText());
                JsonNode result = json.readTree(resultMessage.path("content").asText());
                assertEquals(!id.equals("call-2"), result.path("successful").asBoolean());
                if (!id.equals("call-2")) {
                    assertTrue(result.path("content").asText().contains("cache.evict(id)"));
                }
            }
        }
    }

    @Test
    void duplicateIdsInBatchAreRejectedBeforeAnyToolExecution() throws IOException {
        responses.add(batchResponse("call-1", "call-1", "OrderService.java"));
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        assertEquals(AgentSession.Status.FAILED, session.status());
        assertTrue(session.history().isEmpty());
        assertTrue(session.events().getLast().detail().contains("Duplicate tool call id"));
        assertFalse(session.events().stream().anyMatch(event ->
                event.type() == AgentSession.EventType.TOOL_CALL_REQUESTED));
    }

    @Test
    void malformedSecondCallRejectsEntireBatchBeforeExecution() throws IOException {
        ObjectNode root = (ObjectNode) json.readTree(batchResponse("call-1", "call-2", "OrderService.java"));
        ((ObjectNode) root.path("choices").get(0).path("message").path("tool_calls").get(1)
                .path("function")).put("arguments", "{}");
        responses.add(root.toString());
        var session = new AgentSession("inspect");
        new AgentRuntime(client(Duration.ofSeconds(5)), List.of(readTool), 4).run(session);

        assertEquals(AgentSession.Status.FAILED, session.status());
        assertTrue(session.history().isEmpty());
        assertTrue(session.events().stream().anyMatch(e -> e.detail().contains("INVALID_TOOL_ARGUMENTS")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not-json", "{\"choices\":[]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":null}}]}"})
    void malformedOrEmptyResponsesFail(String response) {
        responses.add(response);
        assertThrows(IllegalStateException.class, () -> client(Duration.ofSeconds(5)).execute(request()));
    }

    @Test
    void requestTimeoutIsReported() {
        responseDelayMillis.set(500);
        responses.add("{}");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> client(Duration.ofMillis(100)).execute(request()));
        assertTrue(failure.getMessage().contains("timed out"));
    }

    @Test
    void invalidConfigurationIsRejectedBeforeAnyRequest() {
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekModelClient(
                " ", baseUrl, "model", Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekModelClient(
                "test-key", URI.create("http://api.deepseek.com"), "model", Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekModelClient(
                "test-key", baseUrl, "model", Duration.ZERO));
        assertTrue(requests.isEmpty());
    }

    private DeepSeekModelClient client(Duration timeout) {
        return new DeepSeekModelClient("test-key", baseUrl, "test-model", timeout);
    }

    @Test
    void includesWorkingMemoryAsReferenceDataBeforePairedToolHistory() throws IOException {
        responses.add(finalResponse("done"));
        var fact = new dev.backendagent.memory.MemoryFact("entry point", "old-read", "read_file",
                "Main.java", "public static void main");
        var retained = new ToolExchange(new ToolCall("recent", "read_file", java.util.Map.of(
                "path", "OrderService.java", "start_line", "1", "end_line", "20")),
                new ToolResult(true, "code evidence"), null, 3);
        client(Duration.ofSeconds(5)).execute(new ModelRequest("inspect", List.of(retained),
                List.of(readTool.definition()), 2, 5, 100, List.of(fact)));
        var messages = requests.getFirst().path("messages");
        assertEquals(6, messages.size());
        assertEquals("user", messages.get(3).path("role").asText());
        var memory = json.readTree(messages.get(3).path("content").asText());
        assertEquals("working_memory_reference_data", memory.path("kind").asText());
        assertEquals("old-read", memory.path("notes").get(0).path("sourceCallId").asText());
        assertEquals("public static void main", memory.path("notes").get(0).path("evidenceQuote").asText());
        assertEquals("recent", messages.get(4).path("tool_calls").get(0).path("id").asText());
        assertEquals("recent", messages.get(5).path("tool_call_id").asText());
    }

    @Test
    void tellsModelWhenOlderHistoryWasOmittedAndReplaysRetainedCall() throws IOException {
        responses.add(finalResponse("done"));
        var retained = new ToolExchange(new ToolCall("recent", "read_file", java.util.Map.of(
                "path", "OrderService.java", "start_line", "1", "end_line", "20")),
                new ToolResult(true, "code evidence"), null, 3);
        client(Duration.ofSeconds(5)).execute(new ModelRequest("inspect", List.of(retained),
                List.of(readTool.definition()), 2, 5, 100));
        var messages = requests.getFirst().path("messages");
        assertTrue(messages.get(0).path("content").asText().contains("省略了较早的 5 条"));
        assertEquals(5, messages.size());
        assertEquals("recent", messages.get(3).path("tool_calls").get(0).path("id").asText());
        assertEquals("recent", messages.get(4).path("tool_call_id").asText());
    }

    @Test
    void appendsNewUserMessageAfterPreviousAnswerAndToolHistory() throws Exception {
        var exchange = new ToolExchange(new ToolCall("first-read", "read_file", java.util.Map.of("path", "Main.java")),
                new ToolResult(true, "old code"), "reading", 1);
        var turns = List.of(
                new dev.backendagent.runtime.ConversationTurn(1, "use 90 percent", dev.backendagent.runtime.AgentSession.Status.COMPLETED, "first answer", 0, 1),
                new dev.backendagent.runtime.ConversationTurn(2, "change to 80 percent", dev.backendagent.runtime.AgentSession.Status.RUNNING, null, 1, 1));
        var request = new ModelRequest("use 90 percent", List.of(exchange), List.of(), 3, 0, 100,
                List.of(), null, null, java.util.Set.of(), dev.backendagent.runtime.WorkspaceState.initial(), List.of(), turns);
        var messages = client(Duration.ofSeconds(5)).buildRequest(request).path("messages");
        var transcript = new java.util.ArrayList<String>();
        for (var message : messages) transcript.add(message.path("content").asText());
        int first = transcript.indexOf("use 90 percent");
        int answer = transcript.indexOf("[历史回答 turn-1-answer；样例数值仅适用当时输入]\nfirst answer");
        int latest = transcript.indexOf("change to 80 percent");
        assertTrue(first < answer && answer < latest);
        assertEquals(messages.size() - 1, latest);
        assertEquals("assistant", messages.get(first + 1).path("role").asText());
        assertEquals("tool", messages.get(first + 2).path("role").asText());
        assertEquals("first answer",turns.getFirst().getAnswer());
    }

    @Test
    void sendsDialogueSourcesWithoutPretendingTheyAreToolObservations() throws Exception {
        var input = new ConversationSummaryRequest("current", null, 0, 1, 4000,
                List.of(new ConversationMessage("turn-1-user",1,"user","满100九折")));
        responses.add(finalResponse("{\"notes\":[{\"kind\":\"PROGRESS\",\"statement\":\"第1轮要求九折\","
                + "\"sourceCallId\":\"turn-1-user\",\"evidenceQuote\":\"满100九折\"}]}"));
        var notes = client(Duration.ofSeconds(5)).summarizeConversation(input);
        assertEquals("turn-1-user",notes.getFirst().getSourceCallId());
        var body=requests.getLast();
        assertFalse(body.has("tools"));
        var reference=json.readTree(body.path("messages").get(1).path("content").asText());
        assertEquals("第1轮用户（turn-1-user）",reference.path("messages").get(0).path("source").asText());
        assertFalse(reference.has("observations"));
        assertEquals("current",reference.path("objective").asText());
        for (var evidence : reference.path("evidence")) {
            assertEquals("turn-1-user",evidence.path("sourceCallId").asText());
            assertFalse(evidence.path("evidenceQuote").asText().contains("current"));
        }
        var instruction=body.path("messages").get(0).path("content").asText();
        assertTrue(instruction.contains("objective仅用于筛选历史相关性，不是摘要事实来源"));
        assertTrue(instruction.contains("不得借用历史evidenceId为其标注来源"));
    }

    private SummaryRequest summaryRequest() {
        var observation = new ToolExchange(new ToolCall("old", "read_file", java.util.Map.of("path", "Main.java")),
                new ToolResult(true, "class Main {}"), null, 1);
        return new SummaryRequest("inspect", null, 0, 1, 2000, List.of(observation));
    }

    @Test void parsesFixedSectionsWithMoreThanEightFactsAndKeepsQuotesInternal() throws Exception {
        var sections = json.createObjectNode();
        for (var section : dev.backendagent.runtime.SummarySection.values()) sections.putArray(section.name());
        for (int i=0;i<9;i++) sections.withArray("FINDINGS").addObject().put("statement", "fact "+i).put("evidenceId", "E1");
        var response=json.createObjectNode();response.set("sections",sections);
        responses.add(finalResponse(response.toString()));
        var notes=client(Duration.ofSeconds(5)).summarize(summaryRequest());
        assertEquals(9,notes.size());
        assertEquals(dev.backendagent.runtime.SummarySection.FINDINGS,notes.getFirst().getSection());
        assertEquals("class Main {}",notes.getFirst().getEvidenceQuote());
        var view=new dev.backendagent.runtime.ContextSummary(1,1,notes).contextData();
        assertFalse(view.toString().contains("evidenceQuote"));
        assertEquals(9,view.path("sections").path("FINDINGS").size());
    }

    @Test void rejectsMissingSectionsAndUnexpectedBulletFields() {
        responses.add(finalResponse("{\"sections\":{\"FINDINGS\":[]}}"));
        assertThrows(RuntimeException.class,()->client(Duration.ofSeconds(5)).summarize(summaryRequest()));
        responses.add(finalResponse("{\"sections\":{\"CONSTRAINTS\":[],\"FINDINGS\":[{\"statement\":\"fact\",\"evidenceId\":\"E1\",\"extra\":\"x\"}],\"CHANGES_AND_TESTS\":[],\"OPEN_ISSUES\":[]}}"));
        assertThrows(RuntimeException.class,()->client(Duration.ofSeconds(5)).summarize(summaryRequest()));
    }

    @Test
    void sendsToolFreeJsonSummaryRequestAndParsesReferencedNotes() throws Exception {
        responses.add(finalResponse("{\"notes\":[{\"kind\":\"PROGRESS\",\"statement\":\"Main exists\","
                + "\"evidenceId\":\"E1\"}]}"));
        var result = client(Duration.ofSeconds(5)).summarize(summaryRequest());
        assertEquals("old", result.getFirst().getSourceCallId());
        assertEquals("class Main {}", result.getFirst().getEvidenceQuote());
        var body = requests.getFirst();
        assertFalse(body.has("tools"));
        assertFalse(body.has("tool_choice"));
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertEquals(2, body.path("messages").size());
        var input = json.readTree(body.path("messages").get(1).path("content").asText());
        assertEquals("class Main {}", input.path("evidence").get(0).path("evidenceQuote").asText());
        assertFalse(input.path("observations").get(0).has("result"));
    }

    @Test
    void progressWarningIsSystemControlAndIncludedInRequestBudget() throws Exception {
        var plain = new ModelRequest("fix source", List.of(), List.of(), 20);
        var warned = plain.withNoProgressReport(new dev.backendagent.runtime.NoProgressReport(8, 0));
        var body = client(Duration.ofSeconds(5)).buildRequest(warned);
        String system = body.path("messages").get(0).path("content").asText();
        assertTrue(system.contains("连续8个完整工具批次"));
        assertTrue(system.contains("不得为了通过检测"));
        assertTrue(client(Duration.ofSeconds(5)).inspectRequest(warned).getRequestBytes()
                > client(Duration.ofSeconds(5)).inspectRequest(plain).getRequestBytes());
        assertFalse(client(Duration.ofSeconds(5)).buildSummaryRequest(summaryRequest()).toString().contains("连续8个完整工具批次"));
    }

    @Test
    void unknownEvidenceRejectsWholeResponseAndReportsOnlySafePosition() {
        responses.add(finalResponse("{\"notes\":[{\"kind\":\"PROGRESS\",\"statement\":\"valid\",\"evidenceId\":\"E1\"},"
                + "{\"kind\":\"PROGRESS\",\"statement\":\"sensitive\",\"evidenceId\":\"Bearer secret-key\"}]}"));
        var failure = assertThrows(SummaryOutputFailure.class,
                () -> client(Duration.ofSeconds(5)).summarize(summaryRequest()));
        assertEquals(1, failure.getNoteIndex());
        var diagnostic = dev.backendagent.runtime.SummaryRejection.from(
                dev.backendagent.runtime.SummaryRejection.Stage.MODEL_SUMMARY, failure, 1, 0, 1);
        assertEquals("UNKNOWN_EVIDENCE_ID", diagnostic.getReasonCode());
        assertEquals("UNKNOWN_EVIDENCE_ID", diagnostic.getIssue());
        assertFalse(diagnostic.detail().contains("secret-key"));
        assertFalse(diagnostic.detail().contains("sensitive"));
    }

    @Test
    void excessiveNotesReportCountWithoutRecordingTheirContent() {
        String note = "{\"kind\":\"PROGRESS\",\"statement\":\"secret\",\"evidenceId\":\"E1\"}";
        responses.add(finalResponse("{\"notes\":[" + String.join(",", java.util.Collections.nCopies(9, note)) + "]}"));
        var failure = assertThrows(SummaryOutputFailure.class,
                () -> client(Duration.ofSeconds(5)).summarize(summaryRequest()));
        assertEquals("NOTE_COUNT", failure.getIssue());
        assertEquals(9, failure.getActualCount());
        assertFalse(failure.getMessage().contains("secret"));
    }

    @Test
    void rejectsDuplicateFieldsInsteadOfSilentlySelectingLastEvidenceId() {
        responses.add(finalResponse("{\"notes\":[{\"kind\":\"PROGRESS\",\"statement\":\"fact\","
                + "\"evidenceId\":\"E999\",\"evidenceId\":\"E1\"}]}"));
        var failure = assertThrows(SummaryOutputFailure.class,
                () -> client(Duration.ofSeconds(5)).summarize(summaryRequest()));
        assertEquals("JSON_SYNTAX", failure.getIssue());
    }

    @Test
    void priorSummaryEvidenceCanBeSelectedWithoutRewritingOriginalQuotation() throws Exception {
        var observation = summaryRequest().getObservations().getFirst();
        var previous = new dev.backendagent.runtime.ContextSummary(1, 1, List.of(
                new dev.backendagent.runtime.SummaryNote(dev.backendagent.runtime.SummaryNote.Kind.PROGRESS,
                        "earlier fact", "prior-call", "exact old quotation")));
        var request = new SummaryRequest("inspect", previous, 1, 2, 2000, List.of(observation));
        responses.add(finalResponse("{\"notes\":[{\"kind\":\"PROGRESS\",\"statement\":\"retained fact\",\"evidenceId\":\"E1\"}]}"));
        var notes = client(Duration.ofSeconds(5)).summarize(request);
        assertEquals("prior-call", notes.getFirst().getSourceCallId());
        assertEquals("exact old quotation", notes.getFirst().getEvidenceQuote());
        var reference = json.readTree(requests.getLast().path("messages").get(1).path("content").asText());
        assertEquals("E1", reference.path("previousSummary").get(0).path("evidenceId").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{}", "{\"notes\":[]}",
            "{\"notes\":[{\"kind\":\"PROGRESS\",\"statement\":\"done\",\"evidenceId\":\"E1\",\"extra\":\"bad\"}]}",
            "{\"notes\":[{\"kind\":\"WRONG\",\"statement\":\"done\",\"evidenceId\":\"E1\"}]}",
            "{\"notes\":[]} {\"notes\":[]}"})
    void rejectsMalformedOrUnexpectedSummaryJson(String content) {
        responses.add(finalResponse(content));
        assertThrows(RuntimeException.class, () -> client(Duration.ofSeconds(5)).summarize(summaryRequest()));
    }

    @Test
    void summarySyntaxAndKindFailuresHaveSafeDistinctMessages() {
        responses.add(finalResponse("not JSON sensitive-response"));
        var syntax = assertThrows(IllegalStateException.class,
                () -> client(Duration.ofSeconds(5)).summarize(summaryRequest()));
        assertEquals("Invalid summary JSON syntax", syntax.getMessage());
        responses.add(finalResponse("{\"notes\":[{\"kind\":\"secret-kind\",\"statement\":\"done\","
                + "\"evidenceId\":\"E1\"}]}"));
        var kind = assertThrows(IllegalArgumentException.class,
                () -> client(Duration.ofSeconds(5)).summarize(summaryRequest()));
        assertEquals("Invalid summary note kind", kind.getMessage());
    }

    @Test
    void rejectsNextStepInNewSummaryResponses() {
        responses.add(finalResponse("{\"notes\":[{\"kind\":\"NEXT_STEP\",\"statement\":\"Run tests next\","
                + "\"evidenceId\":\"E1\"}]}"));
        assertThrows(IllegalStateException.class, () -> client(Duration.ofSeconds(5)).summarize(summaryRequest()));
    }

    @Test
    void incompleteSummaryEnvelopeCannotBeUsed() throws Exception {
        var response = (ObjectNode) json.readTree(finalResponse("{\"notes\":[]}"));
        ((ObjectNode) response.path("choices").get(0)).put("finish_reason", "length");
        responses.add(response.toString());
        assertThrows(IllegalStateException.class, () -> client(Duration.ofSeconds(5)).summarize(summaryRequest()));
    }

    @Test
    void replaysSummaryAsReferenceDataAndPreservesRecentToolPairing() throws Exception {
        responses.add(finalResponse("done"));
        var note = new dev.backendagent.runtime.SummaryNote(dev.backendagent.runtime.SummaryNote.Kind.PROGRESS,
                "Historical observation", "old", "class Main {}");
        var summary = new dev.backendagent.runtime.ContextSummary(1, 1, List.of(note));
        var retained = new ToolExchange(new ToolCall("recent", "read_file", java.util.Map.of("path", "Main.java")),
                new ToolResult(true, "current file"), null, 4);
        client(Duration.ofSeconds(5)).execute(new ModelRequest("inspect", List.of(retained),
                List.of(readTool.definition()), 2, 1, 500, List.of(), null, summary, java.util.Set.of("old")));
        var messages = requests.getFirst().path("messages");
        var reference = json.readTree(messages.get(3).path("content").asText());
        assertEquals("historical_context_summary_reference_data", reference.path("kind").asText());
        assertEquals("old", reference.path("staleSourceCallIds").get(0).asText());
        assertEquals("recent", messages.get(4).path("tool_calls").get(0).path("id").asText());
        assertEquals("recent", messages.get(5).path("tool_call_id").asText());
    }

    private DeepSeekModelClient budgetClient(int window, int output, int margin) {
        return new DeepSeekModelClient(new dev.backendagent.config.ModelConfig("test-key", baseUrl, "test-model",
                Duration.ofSeconds(5), Duration.ofSeconds(5), new RequestBudget(window, output, margin)));
    }

    @Test
    void measurementMatchesActualSerializedHttpBodyIncludingAllReferenceData() throws Exception {
        responses.add(finalResponse("done"));
        var note = new dev.backendagent.runtime.SummaryNote(dev.backendagent.runtime.SummaryNote.Kind.PROGRESS,
                "历史记录", "old", "class Main {}");
        var summary = new dev.backendagent.runtime.ContextSummary(1, 1, List.of(note));
        var fact = new dev.backendagent.memory.MemoryFact("entry point", "old", "read_file", "Main.java", "class Main {}");
        var retained = new ToolExchange(new ToolCall("recent", "read_file", java.util.Map.of(
                "path", "Main.java", "start_line", "1", "end_line", "20")),
                new ToolResult(true, "中😀\n\"quoted\""), "read it", 3);
        var projection = new dev.backendagent.runtime.ContextProjection(1, 2, 1000, 1, 300, 0,
                "call_id=old, tool=read_file\n", List.of(retained));
        var request = new ModelRequest("解释入口", List.of(retained), List.of(readTool.definition()),
                2, 1, 500, List.of(fact), projection, summary, java.util.Set.of("old"));
        var client = budgetClient(65536, 1234, 512);
        var report = client.inspectRequest(request);
        assertTrue(requests.isEmpty()); // Measurement does not send HTTP.
        client.execute(request);
        String wire = json.writeValueAsString(requests.getFirst());
        assertEquals(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, report.getRequestBytes());
        assertEquals(1234, requests.getFirst().path("max_tokens").asInt());
        assertEquals(report.getEstimatedInputTokens() + 1234 + 512, report.getEstimatedTotalTokens());
        assertTrue(wire.contains("historical_context_summary_reference_data"));
        assertTrue(wire.contains("working_memory_reference_data"));
        assertTrue(wire.contains("historical_observation_reference_data"));
        assertTrue(wire.contains("staleSourceCallIds"));
    }

    @Test
    void largeObjectiveOrToolSchemaIsRejectedBeforeAnyHttpRequest() {
        var client = budgetClient(10000, 1000, 1000);
        var hugeObjective = new ModelRequest("中".repeat(5000), List.of(), List.of(readTool.definition()), 1);
        assertFalse(client.inspectRequest(hugeObjective).isWithinBudget());
        assertThrows(IllegalStateException.class, () -> client.execute(hugeObjective));
        var largeTool = new dev.backendagent.tools.ToolDefinition("large", "x".repeat(15000), java.util.Map.of("path", "file"));
        var hugeSchema = new ModelRequest("inspect", List.of(), List.of(largeTool), 1);
        assertFalse(client.inspectRequest(hugeSchema).isWithinBudget());
        assertThrows(IllegalStateException.class, () -> client.execute(hugeSchema));
        assertTrue(requests.isEmpty());
    }

    @Test
    void summaryRequestAlsoReservesOutputAndChecksCompleteBodyBeforeHttp() {
        var client = budgetClient(1000, 500, 100);
        assertThrows(IllegalStateException.class, () -> client.summarize(summaryRequest()));
        assertTrue(requests.isEmpty());
    }

    private ModelRequest request() {
        return new ModelRequest("inspect", List.of(), List.of(readTool.definition()), 4);
    }

    private String toolResponse(String arguments) {
        ObjectNode root = json.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject().put("finish_reason", "tool_calls");
        ObjectNode message = choice.putObject("message").put("role", "assistant")
                .put("content", "我先读取代码。");
        message.putArray("tool_calls").addObject().put("id", "call-1").put("type", "function")
                .putObject("function").put("name", "read_file").put("arguments", arguments);
        return root.toString();
    }

    private String finalResponse(String answer) {
        ObjectNode root = json.createObjectNode();
        root.putArray("choices").addObject().put("finish_reason", "stop")
                .putObject("message").put("role", "assistant").put("content", answer);
        return root.toString();
    }

    private String batchResponse(String firstId, String secondId, String secondPath) throws IOException {
        ObjectNode root = (ObjectNode) json.readTree(toolResponse(
                "{\"path\":\"OrderService.java\",\"start_line\":\"1\",\"end_line\":\"20\"}"));
        var calls = (com.fasterxml.jackson.databind.node.ArrayNode) root.path("choices").get(0)
                .path("message").path("tool_calls");
        ((ObjectNode) calls.get(0)).put("id", firstId);
        ObjectNode second = calls.get(0).deepCopy();
        second.put("id", secondId);
        ObjectNode arguments = json.createObjectNode().put("path", secondPath)
                .put("start_line", "1").put("end_line", "20");
        ((ObjectNode) second.path("function")).put("arguments", arguments.toString());
        calls.add(second);
        return root.toString();
    }
}
