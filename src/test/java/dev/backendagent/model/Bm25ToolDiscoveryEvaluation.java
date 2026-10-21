package dev.backendagent.model;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.config.AgentConfigLoader;
import dev.backendagent.runtime.*;
import dev.backendagent.selection.*;
import dev.backendagent.tools.*;

/** Explicit live experiment, never run by Surefire. Uses read-only local fixture tools, no Docker. */
public final class Bm25ToolDiscoveryEvaluation {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        Path output = Path.of(args[0]);
        Files.createDirectory(output);
        var config = AgentConfigLoader.load(Path.of(args[1]));
        var catalog = new ArrayList<ToolDefinition>();
        for (var item : json.readTree(Path.of(args[2]).toFile())) {
            var f = item.path("function"); var params = new LinkedHashMap<String,String>();
            f.path("parameters").path("properties").fields().forEachRemaining(e -> params.put(e.getKey(),e.getValue().path("description").asText()));
            var required = new HashSet<String>(); f.path("parameters").path("required").forEach(n->required.add(n.asText()));
            catalog.add(new ToolDefinition(f.path("name").asText(),f.path("description").asText(),params,required));
        }
        var report = json.createObjectNode().put("scope","live lexical-vs-BM25 query discovery microtasks; controlled omission; no coding-task success-rate claim");
        var runs = report.putArray("runs");
        for (String scenario : List.of("natural", "calculator", "empty-then-wider")) {
            var modes = List.of("lexical","bm25");
            for (String mode : modes) {
                String id = scenario + "-" + mode; Path dir=output.resolve(id); Files.createDirectory(dir);
                Path repo=dir.resolve("workspace"); Files.createDirectories(repo.resolve("src/main/java/example"));
                Files.writeString(repo.resolve("src/main/java/example/OrderAudit.java"),"package example;\nfinal class OrderAudit {\n    static final String ROUTE = \"AUDIT_ROUTE_K7\";\n}\n");
                var workspace = new Workspace(repo,Path.of(args[1]));
                var realTools = Map.<String,Tool>of("read_file",new ReadFileTool(workspace),"list_files",new ListFilesTool(workspace),"search_code",new SearchCodeTool(workspace),"integer_calculate",new IntegerCalculateTool());
                var tools = new ArrayList<Tool>();
                for (var definition : catalog) tools.add(new Tool() {
                    public String name() { return definition.getName(); }
                    public ToolDefinition definition() { return definition; }
                    public ToolResult execute(Map<String,String> arguments) {
                        var real=realTools.get(name());
                        return real==null ? new ToolResult(false,"本只读验证任务不支持执行该操作；请使用文件查找能力。") : real.execute(arguments);
                    }
                });
                String task="查找工作区中包含字面关键词 AUDIT_ROUTE_K7 的文件，返回实际文件路径和该行原文。文件路径未知，不要猜路径，不修改文件。";
                if (scenario.equals("calculator")) task="先通过search_tools查找整数计算能力，再实际使用计算工具计算 (1200+350)*3，返回计算结果。不要读写文件，不要仅凭心算回答。";
                if (scenario.startsWith("empty")) task += "另外验证工具发现的恢复：先调用search_tools，query严格为nebula_quasar_capability；随后完成上述文件查找任务。";
                var session=new AgentSession(task);
                var metrics=new ArrayList<ModelCallMetrics>(); var adapter=new DeepSeekModelClient(config,metrics::add,false);
                var transcript=json.createArrayNode();
                ModelClient client=request->{
                    try {
                        int n=transcript.size()+1;
                        Files.writeString(dir.resolve(String.format("request-%02d.json",n)),json.writerWithDefaultPrettyPrinter().writeValueAsString(adapter.buildRequest(request)));
                        var response=adapter.execute(request);
                        transcript.add(json.valueToTree(response));
                        Files.writeString(dir.resolve("responses.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(transcript));
                        return response;
                    } catch(java.io.IOException failure) { throw new IllegalStateException("Cannot save evaluation request",failure); }
                };
                var runtime=new AgentRuntime(client,tools,8,new ContextBudget(64000),message->{},false);
                runtime.withToolSelection((t,h,c,k)->c.stream()
                        .filter(d->!Set.of("search_code","list_files","integer_calculate").contains(d.getName())).limit(8).toList(),8,
                        mode.equals("bm25")?new Bm25ToolSearchRanker():new LexicalToolSearchRanker());
                System.out.println("START "+id);
                runtime.run(session);
                var run=runs.addObject().put("id",id).put("status",session.status().name()).put("modelCalls",session.modelCalls())
                        .put("answer",session.answer()==null?"":session.answer());
                run.set("metrics",json.valueToTree(metrics));
                run.put("requestBytes",metrics.stream().mapToLong(ModelCallMetrics::getRequestBytes).sum());
                run.put("totalTokens",metrics.stream().filter(m->m.getTotalTokens()!=null).mapToLong(ModelCallMetrics::getTotalTokens).sum());
                var exchanges=run.putArray("exchanges");
                session.history().forEach(x->exchanges.add(json.valueToTree(x)));
                boolean calculation=scenario.equals("calculator");
                boolean executed=session.history().stream().anyMatch(x->x.call().name().equals(calculation?"integer_calculate":"search_code")
                        && x.result().successful() && x.result().content().contains(calculation?"value=4650":"AUDIT_ROUTE_K7"));
                boolean passed=session.status()==AgentSession.Status.COMPLETED && executed
                        && session.answer().contains(calculation?"4650":"OrderAudit.java")
                        && (calculation || session.answer().contains("AUDIT_ROUTE_K7"));
                run.put("accepted",passed);
                boolean firstMiss=session.history().stream().filter(x->x.call().name().equals("search_tools")).findFirst().map(x->x.result().content().contains("NO_MATCH")).orElse(false);
                boolean changedQuery=session.history().stream().filter(x->x.call().name().equals("search_tools")).map(x->x.call().arguments().get("query")).distinct().count()>1;
                run.put("firstSearchMiss",firstMiss).put("changedQuery",changedQuery);
                run.put("searchExecuted",session.history().stream().anyMatch(x->x.call().name().equals("search_tools")));
                run.put("automaticRecoveries",session.events().stream().filter(e->e.type()==AgentSession.EventType.MODEL_RETRY_SCHEDULED && e.detail().startsWith("toolSelectionRecovery=")).count());
                Files.writeString(output.resolve("report.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                System.out.println("END "+id+" accepted="+passed+" calls="+session.modelCalls()+" firstMiss="+firstMiss+" changedQuery="+changedQuery);
            }
        }
    }
}
