package dev.backendagent.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import dev.backendagent.config.*;
import dev.backendagent.history.*;
import dev.backendagent.persistence.*;
import dev.backendagent.runtime.*;
import dev.backendagent.sandbox.*;
import dev.backendagent.tools.*;
import dev.backendagent.verification.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** One-round real-model evaluator; checkpoint_retry.py isolates and bounds outer retries. */
public final class CheckpointRetryEvaluation {
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();
    static Path base; static volatile int stage,httpCalls; static long reportedTokens;
    static volatile int activeModelCall; static ModelConfig provider; static HttpServer proxy; static ObjectNode report;
    static DockerSandboxExecutor executor;
    static boolean verificationBasisEnabled;
    static boolean toolSelectionEnabled;
    static String toolSearchRanker;
    static int perTurnModelCallLimit;
    public static void main(String[] args)throws Exception {
        base=Path.of(args[0]).toAbsolutePath();
        verificationBasisEnabled=!Files.exists(base.resolve("disable-verification-basis"));
        toolSelectionEnabled=Files.exists(base.resolve("tool-selection-rule"));
        toolSearchRanker=Files.exists(base.resolve("tool-search-ranker.txt"))
                ? Files.readString(base.resolve("tool-search-ranker.txt")).trim() : "lexical";
        if (!Set.of("lexical","bm25").contains(toolSearchRanker)) throw new IllegalArgumentException("Invalid tool search ranker");
        if (!toolSelectionEnabled && toolSearchRanker.equals("bm25")) throw new IllegalArgumentException("BM25 requires tool selection");
        perTurnModelCallLimit=Files.exists(base.resolve("model-call-limit.txt"))
                ? Integer.parseInt(Files.readString(base.resolve("model-call-limit.txt")).trim()) : 50;
        if (perTurnModelCallLimit < 1 || perTurnModelCallLimit > 50) throw new IllegalArgumentException("Invalid evaluation call limit");
        boolean resume=args.length==3&&args[2].equals("--resume-completed");
        if(Files.exists(base.resolve("report.json"))&&!resume)throw new IllegalStateException("Never overwrite a previous run");
        report=resume?(ObjectNode)JSON.readTree(base.resolve("report.json").toFile()):JSON.createObjectNode().put("status","PREFLIGHT").put("startedAt",Instant.now().toString())
            .put("method","Fresh continuous production session; real tools and model; no injected history or external grading feedback")
            .putNull("tokenLimit").putNull("externalCallLimit").putNull("productionTurnLimit").put("plannedTurns",JSON.readTree(base.resolve("stages.json").toFile()).size()).put("perTurnModelCallLimit",perTurnModelCallLimit).put("frozenJarSha256",sha(base.resolve("agent.jar")));
        if(resume) {
            report.withArray("harnessInterruptions").addObject().put("reason","Automatic rollback to previous accepted boundary; unchanged task and model; failed attempt excluded from model context")
                .put("recoveredAt",Instant.now().toString());
            httpCalls=report.path("externalApiCalls").asInt();reportedTokens=report.path("providerReportedTokens").asLong();
            report.remove("hostFailure");
        }
        save();
        var runner=new LocalProcessRunner();
        executor=new DockerSandboxExecutor(runner,DockerSandboxExecutor.DEFAULT_IMAGE,Duration.ofSeconds(120));
        if(!resume) {
        copy(base.resolve("seed"),base.resolve("source"));
        var broken=grade(new Workspace(base.resolve("source"),Path.of(args[1])),1,"preflight-broken");
        report.put("graderRejectsBrokenParser",!broken.successful());
        if(broken.successful())throw new IllegalStateException("Grader did not detect the broken parser");
        }
        provider=AgentConfigLoader.load(Path.of(args[1]));
        report.put("verificationBasisEnabled",verificationBasisEnabled);
        report.put("toolSelection",toolSelectionEnabled?"rule":"all");
        report.put("toolSearchRanker",toolSelectionEnabled?toolSearchRanker:"not_enabled");
        report.put("model",provider.getModelName()).put("status","RUNNING"); save();
        startProxy();
        var local=new ModelConfig("evaluation-loopback",URI.create("http://127.0.0.1:"+proxy.getAddress().getPort()),provider.getModelName(),
            provider.getConnectTimeout(),provider.getRequestTimeout().plusSeconds(15),provider.getRequestBudget());
        try(var store=new FileSessionStore(base.resolve("sessions"))) {
            var stages=JSON.readTree(base.resolve("stages.json").toFile());
            var turns=report.withArray("turns");
            final AgentSession session;final DockerWorkspace workspace;
            if(resume) {
                var id=UUID.fromString(report.path("sessionId").asText());
                workspace=DockerWorkspace.open(base.resolve("source"),store.sessionDirectory(id),runner,DockerSandboxExecutor.DEFAULT_IMAGE);
                session=store.openCompleted(id,workspace,Integer.MAX_VALUE);
                if(session.turns().size()!=turns.size())throw new IllegalStateException("Completed turn count mismatch");
                var last=(ObjectNode)turns.get(turns.size()-1);
                var checked=grade(workspace,turns.size(),"turn-"+turns.size()+"-after-harness-fix");
                last.put("independentTestsPassed",checked.successful()).put("taskPassed",checked.successful());
            } else {
                session=new AgentSession(stages.get(0).asText(),store); store.create(session);
                workspace=DockerWorkspace.create(new Workspace(base.resolve("source"),Path.of(args[1]),base.resolve("sessions")),store.sessionDirectory(session.id()),runner,DockerSandboxExecutor.DEFAULT_IMAGE);
            }
            store.bindWorkspace(workspace);
            report.put("sessionId",session.id().toString()); save();
            var client=new DeepSeekModelClient(local,verificationBasisEnabled);
            ModelClient observed=new ModelClient(){
                public RequestBudgetReport inspectRequest(ModelRequest r){
                    var result=client.inspectRequest(r);
                    record("budget-inspections.jsonl",JSON.createObjectNode().put("turn",stage).put("modelCall",session.modelCalls())
                        .put("toolExchanges",r.history().size()).put("visibleTurns",r.turns().size())
                        .put("toolSummaryCharacters",r.contextSummary()==null?0:r.contextSummary().characterCount())
                        .put("conversationSummaryCharacters",r.conversationSummary()==null?0:r.conversationSummary().characterCount())
                        .set("budget",JSON.valueToTree(result)));
                    return result;
                }
                public ModelResponse execute(ModelRequest r){
                    activeModelCall=session.modelCalls();
                    record("task-views.jsonl",JSON.createObjectNode().put("turn",stage).put("modelCall",session.modelCalls())
                        .put("availableToolCount",r.availableTools().size()).putPOJO("availableTools",r.availableTools().stream().map(ToolDefinition::getName).toList())
                        .put("historicalEvidenceEntries",r.historicalEvidence().size()).put("projectionVersion",r.contextProjection().getPolicyVersion())
                        .put("indexCharacters",r.contextProjection().getRecoveryReferences().length())
                        .set("verification",JSON.valueToTree(r.verificationContext())));
                    var cache=JSON.createArrayNode();for(var e:r.historicalEvidence())cache.addObject().put("sourceCallId",e.getSourceCallId()).put("currentValidity",e.getCurrentValidity().name());
                    record("evidence-views.jsonl",JSON.createObjectNode().put("turn",stage).put("modelCall",session.modelCalls()).set("entries",cache));
                    return client.execute(r);
                }
                public boolean supportsVerificationUpdates(){return client.supportsVerificationUpdates();}
                public boolean supportsVerificationCompaction(){return client.supportsVerificationCompaction();}
                public List<ActiveVerificationBasis> compactVerificationBasis(VerificationCompactionRequest r){
                    activeModelCall=session.modelCalls();
                    record("verification-compaction-inputs.jsonl",JSON.createObjectNode().put("turn",stage).put("modelCall",session.modelCalls()).set("input",r.referenceData()));
                    var result=client.compactVerificationBasis(r);
                    record("verification-compaction-results.jsonl",JSON.createObjectNode().put("turn",stage).put("modelCall",session.modelCalls()).set("bases",JSON.valueToTree(result)));
                    return result;
                }
                public List<ActiveVerificationBasis> updateVerificationBasis(VerificationUpdateRequest r){
                    activeModelCall=session.modelCalls();
                    record("verification-inputs.jsonl",JSON.createObjectNode().put("turn",stage).put("modelCall",session.modelCalls()).set("input",r.referenceData()));
                    try {
                        var result=client.updateVerificationBasis(r);
                        record("verification-accepted.jsonl",JSON.createObjectNode().put("turn",stage).put("modelCall",session.modelCalls()).set("bases",JSON.valueToTree(result)));
                        return result;
                    } catch(RuntimeException e){
                        record("verification-rejected.jsonl",JSON.createObjectNode().put("turn",stage).put("modelCall",session.modelCalls()).put("error",e.getClass().getSimpleName()));
                        throw e;
                    }
                }
                public boolean supportsSummarization(){return client.supportsSummarization();}
                public List<SummaryNote> summarize(SummaryRequest r){
                    activeModelCall=session.modelCalls();
                    var result=client.summarize(r); notes("TOOLS",result,session); return result;
                }
                public boolean supportsConversationSummarization(){return client.supportsConversationSummarization();}
                public List<SummaryNote> summarizeConversation(ConversationSummaryRequest r){
                    activeModelCall=session.modelCalls();
                    var result=client.summarizeConversation(r); notes("CONVERSATION",result,session); return result;
                }
                public boolean supportsSummaryRefinement(){return client.supportsSummaryRefinement();}
                public List<SummaryNote> refineSummary(SummaryRefinementRequest r){
                    activeModelCall=session.modelCalls();
                    var result=client.refineSummary(r); notes("REFINEMENT",result,session); return result;
                }
            };
            var archive=store.observationArchive(session);
            // Same tool list and order as AgentApplication's production path.
            var tools=List.of(new ListFilesTool(workspace),new ReadFileTool(workspace),new PinVerificationBasisTool(session,workspace),new IntegerCalculateTool(),
                new ReadObservationTool(session,workspace,archive),new SearchHistoryTool(new HistoryArchiveReader(archive,workspace)),
                new SearchCodeTool(workspace),new SearchTurnsTool(session),new ReadTurnTool(session),new RememberFactTool(session),
                new ApplyPatchTool(workspace,session),new CreateFileTool(workspace,session),new WorkspaceDiffTool(workspace),new RunTestsTool(workspace,executor));

            boolean allCompleted=true; boolean allPassed=true;
            for(stage=resume?turns.size()+1:1;stage<=stages.size();stage++) {
                int callsBefore=session.modelCalls(),httpBefore=httpCalls; long tokenBefore=reportedTokens,eventBefore=session.events().size();
                String input=stages.get(stage-1).asText();
                var row=turns.addObject().put("turn",stage).put("status","RUNNING").put("startedAt",Instant.now().toString());
                report.put("currentTurn",stage); save();
                System.out.println("START turn="+stage+" time="+Instant.now());
                var runtime=new AgentRuntime(observed,tools,callsBefore+perTurnModelCallLimit,new ContextBudget(ContextBudget.DEFAULT_MAX_HISTORY_CHARACTERS),System.out::println,verificationBasisEnabled);
                if (toolSelectionEnabled) runtime.withToolSelection(new dev.backendagent.selection.RuleToolSelector(),8,
                        toolSearchRanker.equals("bm25") ? new dev.backendagent.selection.Bm25ToolSearchRanker()
                                : new dev.backendagent.selection.LexicalToolSearchRanker());
                boolean hostFailed=false;
                try {if(stage==1)runtime.run(session);else runtime.continueConversation(session,input);}
                catch(Exception e){hostFailed=true;row.put("hostFailure",e.getClass().getSimpleName());if(e instanceof dev.backendagent.persistence.PersistenceException)e.printStackTrace(System.out);}
                if(!hostFailed)store.saveSnapshot(session);
                row.put("status",session.status().name()).put("modelCalls",session.modelCalls()-callsBefore)
                    .put("externalCalls",httpCalls-httpBefore).put("tokens",reportedTokens-tokenBefore)
                    .put("workspaceRevision",session.workspaceState().getRevision()).put("finishedAt",Instant.now().toString());
                var counts=row.putObject("eventCounts");
                session.events().stream().skip(eventBefore).forEach(e->counts.put(e.type().name(),counts.path(e.type().name()).asInt()+1));
                var anomalies=row.putArray("anomalies");
                session.events().stream().skip(eventBefore).filter(e->Set.of("TOOL_EXECUTION_FAILED","COMPACTION_FAILED","CONVERSATION_COMPACTION_FAILED","FINISH_REJECTED","NO_PROGRESS_WARNING","MODEL_CALL_FAILED","SESSION_FAILED","VERIFICATION_UPDATE_FAILED").contains(e.type().name()))
                    .forEach(e->anomalies.addObject().put("sequence",e.sequence()).put("type",e.type().name()).put("detail",e.detail()));
                save();
                boundary(store,session,input);
                Files.writeString(base.resolve("turn-"+stage+"-answer.txt"),Objects.toString(session.answer(),""));
                if(hostFailed||session.status()!=AgentSession.Status.COMPLETED){
                    row.put("taskPassed",false);allCompleted=false;allPassed=false;
                    report.put("stopReason",session.status()==AgentSession.Status.BUDGET_EXHAUSTED?"ABNORMAL_PER_TURN_MODEL_CALL_COUNT":"PRODUCTION_SESSION_CANNOT_CONTINUE"); save(); break;
                }
                // Only grade a disposable snapshot. Do not execute tools or mutate the live session.
                var result=grade(workspace,stage,"turn-"+stage);
                row.put("independentTestsPassed",result.successful());
                allPassed &= result.successful();
                row.put("taskPassed",result.successful());
                save();
                System.out.println("END turn="+stage+" status="+session.status()+" calls="+(session.modelCalls()-callsBefore)+" tokens="+(reportedTokens-tokenBefore)+" independentPassed="+result.successful());
                // Passive grading only: independent failure does not interrupt a usable session.
            }
            report.put("status",allCompleted?"ALL_PLANNED_TURNS_COMPLETED":"STOPPED")
                .put("allCompleted",allCompleted).put("allIndependentTestsPassed",allPassed).put("finishedAt",Instant.now().toString()); save();
        } catch(Exception e){report.put("status","HARNESS_FAILED").put("hostFailure",e.getClass().getSimpleName());save();throw e;}
        finally{proxy.stop(0);}
    }
    static void notes(String kind,List<SummaryNote> notes,AgentSession session){
        record("summary-candidates.jsonl",JSON.createObjectNode().put("turn",stage).put("modelCall",session.modelCalls()).put("kind",kind).set("notes",JSON.valueToTree(notes)));
    }
    static void startProxy()throws Exception {
        proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        proxy.createContext("/chat/completions",exchange->{
            try {
                String body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                var node=JSON.readTree(body);int id=++httpCalls;int callTurn=stage;
                Path dir=base.resolve("http/call-"+String.format("%04d",id));Files.createDirectories(dir);
                Files.writeString(dir.resolve("request.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
                save(); long start=System.nanoTime();
                var endpoint=URI.create(provider.getBaseUrl().toString().replaceAll("/+$","")+"/chat/completions");
                var response=HTTP.send(HttpRequest.newBuilder(endpoint).timeout(provider.getRequestTimeout())
                    .header("Authorization","Bearer "+provider.getApiKey()).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
                String content=response.body();
                if(content.contains(provider.getApiKey()))throw new IllegalStateException("Credential echoed by provider");
                Files.writeString(dir.resolve("response.json"),content);
                JsonNode usage=JSON.nullNode();long actual=-1;
                try{usage=JSON.readTree(content).path("usage");if(usage.path("total_tokens").isIntegralNumber())actual=usage.path("total_tokens").asLong();}catch(Exception ignored){}
                if(actual>=0)reportedTokens+=actual;
                String prompt=node.path("messages").path(0).path("content").asText();
                String kind=prompt.startsWith("精简本轮测试生成依据")?"VERIFICATION_COMPACTION":prompt.startsWith("维护本轮测试生成依据")?"VERIFICATION":node.has("response_format")?"SUMMARY":"TASK";
                var metric=JSON.createObjectNode().put("call",id).put("turn",callTurn).put("modelCall",activeModelCall).put("kind",kind).put("httpStatus",response.statusCode())
                    .put("elapsedMillis",(System.nanoTime()-start)/1000000).put("requestBytes",body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .put("toolDefinitionCount",node.path("tools").size())
                    .put("toolDefinitionBytes",JSON.writeValueAsBytes(node.path("tools")).length);
                metric.set("usage",usage);record("api.jsonl",metric);save();
                System.out.println("API call="+id+" turn="+callTurn+" kind="+kind+" status="+response.statusCode()+" tokens="+actual);
                reply(exchange,response.statusCode(),content);
            }catch(Exception e){record("transport-errors.jsonl",JSON.createObjectNode().put("turn",stage).put("error",e.getClass().getSimpleName()));
                try{reply(exchange,502,"{\"error\":{\"message\":\"EVALUATION_TRANSPORT_FAILURE\"}}");}catch(Exception ignored){}
            }finally{exchange.close();}
        });proxy.start();
    }
    static ToolResult grade(WorkspaceAccess workspace,int turn,String label)throws Exception {
        Path snapshot=workspace.createTestSnapshot();
        try{
            Files.writeString(snapshot.resolve("pom.xml"),Files.readString(base.resolve("seed/pom.xml")));
            Path tests=snapshot.resolve("src/test");if(Files.exists(tests))try(var paths=Files.walk(tests)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
            Path test=snapshot.resolve("src/test/java/telemetry/PrivateContractTest.java");Files.createDirectories(test.getParent());
            Files.copy(base.resolve("private-tests/stage-"+turn+".java"),test);
            var result=executor.runTests(new Workspace(snapshot,Path.of("agent-local.properties")));
            Files.writeString(base.resolve(label+"-grade.txt"),result.content());return result;
        }finally{try(var paths=Files.walk(snapshot)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    static void boundary(FileSessionStore store,AgentSession session,String input)throws Exception{
        Path dest=base.resolve("boundaries/turn-"+stage);copy(store.sessionDirectory(session.id()),dest.resolve("session"));
        Files.writeString(dest.resolve("input.txt"),input);Files.writeString(dest.resolve("answer.txt"),Objects.toString(session.answer(),""));
        var manifest=JSON.createObjectNode().put("turn",stage).put("sessionId",session.id().toString()).put("status",session.status().name())
            .put("modelCalls",session.modelCalls()).put("jarSha256",sha(base.resolve("agent.jar")));
        var hashes=manifest.putObject("sha256");try(var paths=Files.walk(dest)){
            for(var p:paths.filter(Files::isRegularFile).sorted().toList())hashes.put(dest.relativize(p).toString(),sha(p));
        }
        Files.writeString(dest.resolve("manifest.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
    }
    static void copy(Path from,Path to)throws Exception{try(var paths=Files.walk(from)){for(var p:paths.toList()){
        Path dest=to.resolve(from.relativize(p));if(Files.isDirectory(p)){Files.createDirectories(dest);Files.setPosixFilePermissions(dest,Files.getPosixFilePermissions(p));}else Files.copy(p,dest);
    }}}
    static String sha(Path p)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    static void record(String name,JsonNode node){try{Files.writeString(base.resolve(name),JSON.writeValueAsString(node)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new IllegalStateException("Cannot record evaluation data",e);}}
    static void reply(com.sun.net.httpserver.HttpExchange e,int status,String body)throws Exception{byte[] data=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,data.length);e.getResponseBody().write(data);}
    static synchronized void save()throws Exception{report.put("externalApiCalls",httpCalls).put("providerReportedTokens",reportedTokens);Path tmp=base.resolve("report.pending.json");Files.writeString(tmp,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));Files.move(tmp,base.resolve("report.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
}
