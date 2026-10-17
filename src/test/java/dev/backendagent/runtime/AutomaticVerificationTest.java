package dev.backendagent.runtime;

import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import dev.backendagent.verification.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticVerificationTest {
    @TempDir Path root;
    private Workspace workspace() throws Exception { return new Workspace(root,root.resolve("agent-local.properties")); }
    private static ActiveVerificationBasis available(VerificationSource s) {
        return new ActiveVerificationBasis(s,s.content().strip(),"AVAILABLE","",null,null);
    }
    private abstract static class UpdatingModel implements ModelClient {
        public boolean supportsVerificationUpdates(){return true;}
        public List<ActiveVerificationBasis> updateVerificationBasis(VerificationUpdateRequest r) {
            return List.of(available(r.sources().stream().filter(s->s.userRequirement()&&s.turnId()==r.turnId()).findFirst().orElseThrow()));
        }
    }
    @Test void oversizedBasisCompactsOnceAndReservesTaskCall() throws Exception {
        var w=workspace();var session=new AgentSession("规则".repeat(300));var compactCalls=new AtomicInteger();
        var runtime=new AgentRuntime(new UpdatingModel(){
            public List<ActiveVerificationBasis> updateVerificationBasis(VerificationUpdateRequest r){return Collections.nCopies(12,super.updateVerificationBasis(r).getFirst());}
            public boolean supportsVerificationCompaction(){return true;}
            public List<ActiveVerificationBasis> compactVerificationBasis(VerificationCompactionRequest r){compactCalls.incrementAndGet();return List.of(r.candidate().getFirst());}
            public ModelResponse execute(ModelRequest r){assertEquals(1,r.verificationContext().activeBases().size());return ModelResponse.finish("done");}
        },List.of(new PinVerificationBasisTool(session,w)),3);
        runtime.run(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(3,session.modelCalls());assertEquals(1,compactCalls.get());
        assertTrue(session.events().stream().anyMatch(e->e.type()==AgentSession.EventType.VERIFICATION_COMPACTION_COMPLETED));
    }
    @Test void compactionFailureOrInsufficientBudgetNeverLoopsOrPersistsOversizedCandidate() throws Exception {
        for(int budget:List.of(2,5)) {
            var w=workspace();var session=new AgentSession("规则".repeat(300));var compactCalls=new AtomicInteger();
            new AgentRuntime(new UpdatingModel(){
                public List<ActiveVerificationBasis> updateVerificationBasis(VerificationUpdateRequest r){return Collections.nCopies(12,super.updateVerificationBasis(r).getFirst());}
                public boolean supportsVerificationCompaction(){return true;}
                public List<ActiveVerificationBasis> compactVerificationBasis(VerificationCompactionRequest r){compactCalls.incrementAndGet();return r.candidate();}
                public ModelResponse execute(ModelRequest r){assertTrue(r.verificationContext()==null||r.verificationContext().activeBases().isEmpty());return ModelResponse.finish("done");}
            },List.of(new PinVerificationBasisTool(session,w)),budget).run(session);
            assertEquals(AgentSession.Status.COMPLETED,session.status(),session.events().toString());assertEquals(budget==2?0:1,compactCalls.get());
            assertTrue(session.verificationLedger().bases().isEmpty());assertEquals(budget==2?2:3,session.modelCalls());
        }
    }
    @Test void sameFileHashDoesNotRefreshOnNewCallIdsFormatsOrPartialReadsButChangedHashDoes() throws Exception {
        var w=workspace();Files.writeString(root.resolve("contract.md"),"原规则A\n原规则B\n");
        var session=new AgentSession("按contract.md核验");var updates=new AtomicInteger();var step=new AtomicInteger();
        var model=new UpdatingModel(){
            public List<ActiveVerificationBasis> updateVerificationBasis(VerificationUpdateRequest r){updates.incrementAndGet();return super.updateVerificationBasis(r);}
            public ModelResponse execute(ModelRequest r){return switch(step.getAndIncrement()){
                case 0 -> ModelResponse.callTool(new ToolCall("first","read_file",Map.of("path","contract.md","start_line","1","end_line","1")));
                case 1 -> ModelResponse.callTool(new ToolCall("raw","read_file",Map.of("path","contract.md","start_line","1","end_line","2","format","raw")));
                case 2 -> ModelResponse.callTool(new ToolCall("again","read_file",Map.of("path","contract.md","start_line","2","end_line","2")));
                case 3 -> ModelResponse.callTool(new ToolCall("edit","apply_patch",Map.of("path","contract.md","old_text","原规则A","new_text","新规则A")));
                case 4 -> ModelResponse.callTool(new ToolCall("changed","read_file",Map.of("path","contract.md","start_line","1","end_line","2","format","raw")));
                default -> ModelResponse.finish("done");};}
        };
        new AgentRuntime(model,List.of(new PinVerificationBasisTool(session,w),new ReadFileTool(w),new ApplyPatchTool(w,session)),15).run(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status(),session.events().toString());
        assertEquals(3,updates.get()); // turn start, first version, changed version
        assertEquals(w.fileFingerprint("contract.md").getSha256(),session.verificationLedger().attemptedFileVersions().get("contract.md"));
    }
    @Test void failedFileRefreshDoesNotRetryWhenUnchangedFileIsReadAgain() throws Exception {
        var w=workspace();Files.writeString(root.resolve("contract.md"),"规则\n");var session=new AgentSession("核验contract.md");
        var updates=new AtomicInteger();var step=new AtomicInteger();
        new AgentRuntime(new UpdatingModel(){
            public List<ActiveVerificationBasis> updateVerificationBasis(VerificationUpdateRequest r){updates.incrementAndGet();throw new IllegalArgumentException("invalid output");}
            public ModelResponse execute(ModelRequest r){int n=step.getAndIncrement();return n<3?
                    ModelResponse.callTool(new ToolCall("read"+n,"read_file",Map.of("path","contract.md","start_line","1","end_line","1","format",n==1?"raw":"numbered"))):ModelResponse.finish("done");}
        },List.of(new PinVerificationBasisTool(session,w),new ReadFileTool(w)),10).run(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(2,updates.get());
        assertEquals(1,session.verificationLedger().attemptedFileVersions().size());
    }
    @Test void automaticallyRefreshesAtTurnStartAndCarriesSupersessionWithLaterUserSource() throws Exception {
        var w=workspace();var session=new AgentSession("仅统计5xx");var updates=new AtomicInteger();
        ModelClient model=new UpdatingModel(){
            public List<ActiveVerificationBasis> updateVerificationBasis(VerificationUpdateRequest r){
                updates.incrementAndGet();var current=r.sources().stream().filter(s->s.userRequirement()&&s.turnId()==r.turnId()).findFirst().orElseThrow();
                if(r.turnId()==1)return List.of(available(current));
                var old=r.sources().stream().filter(s->s.id().equals("turn-1-user")).findFirst().orElseThrow();
                return List.of(available(current),new ActiveVerificationBasis(old,"仅统计5xx","SUPERSEDED","本轮更改告警规则",current,current.content()));
            }
            public ModelResponse execute(ModelRequest r){
                assertFalse(r.verificationContext().activeBases().isEmpty());
                return ModelResponse.finish("本轮完成");
            }
        };
        var runtime=new AgentRuntime(model,List.of(new PinVerificationBasisTool(session,w)),10);
        runtime.run(session);runtime.continueConversation(session,"统计4xx和5xx，排除429；429仍计入4xx桶");
        assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(2,updates.get());
        assertEquals(4,session.modelCalls());
        var bases=new ContextAssembler().assemble(session,List.of(),3).verificationContext().activeBases();
        assertTrue(bases.stream().anyMatch(b->b.status().equals("SUPERSEDED")&&b.replacement().turnId()==2));
    }
    @Test void refreshesAfterRelatedReadButNotDirectoryListingOrUnrelatedJavaRead() throws Exception {
        var w=workspace();Files.writeString(root.resolve("contract.md"),"429仍计入4xx桶\n");
        Files.writeString(root.resolve("Unrelated.java"),"class Unrelated {}\n");
        var session=new AgentSession("按contract.md生成回归测试");var updates=new AtomicInteger();var task=new AtomicInteger();
        ModelClient model=new UpdatingModel(){
            public List<ActiveVerificationBasis> updateVerificationBasis(VerificationUpdateRequest r){updates.incrementAndGet();return super.updateVerificationBasis(r);}
            public ModelResponse execute(ModelRequest r){return switch(task.getAndIncrement()){
                case 0 -> ModelResponse.callTool(new ToolCall("read","read_file",Map.of("path","contract.md","format","raw","start_line","1","end_line","1")));
                case 1 -> ModelResponse.callTool(new ToolCall("list","list_files",Map.of("path",".")));
                case 2 -> ModelResponse.callTool(new ToolCall("other","read_file",Map.of("path","Unrelated.java","format","raw","start_line","1","end_line","1")));
                default -> ModelResponse.finish("done");};}
        };
        new AgentRuntime(model,List.of(new PinVerificationBasisTool(session,w),new ReadFileTool(w),new ListFilesTool(w)),10).run(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(2,updates.get());assertEquals(6,session.modelCalls());
    }
    @Test void invalidAutomaticOutputDoesNotLoopOrReplaceOriginalRequirements() throws Exception {
        var w=workspace();var s=new AgentSession("保留解析约束");var updates=new AtomicInteger();
        ModelClient model=new UpdatingModel(){
            public List<ActiveVerificationBasis> updateVerificationBasis(VerificationUpdateRequest r){updates.incrementAndGet();throw new IllegalArgumentException("provider returned secret");}
            public ModelResponse execute(ModelRequest r){assertEquals("保留解析约束",r.turns().getLast().getUserMessage());return ModelResponse.finish("done");}
        };
        new AgentRuntime(model,List.of(new PinVerificationBasisTool(s,w)),5).run(s);
        assertEquals(AgentSession.Status.COMPLETED,s.status());assertEquals(1,updates.get());
        assertTrue(s.events().stream().anyMatch(e->e.type()==AgentSession.EventType.VERIFICATION_UPDATE_FAILED));
        assertFalse(s.events().toString().contains("secret"));
    }
    @Test void reservesLastCallForTaskInsteadOfSpendingItOnBasisMaintenance() throws Exception {
        var w=workspace();var s=new AgentSession("inspect");
        new AgentRuntime(new UpdatingModel(){public ModelResponse execute(ModelRequest r){return ModelResponse.finish("done");}},
                List.of(new PinVerificationBasisTool(s,w)),1).run(s);
        assertEquals(AgentSession.Status.COMPLETED,s.status());assertEquals(1,s.modelCalls());assertNull(s.verificationLedger());
    }
    @Test void rejectedInstallBudgetPreservesPreviouslyAvailableContractAndDoesNotRetryUnchangedInput() throws Exception {
        var w=workspace();Files.writeString(root.resolve("contract.md"),"所有状态均参与百分位\n");
        var s=new AgentSession("按contract.md检查百分位");var updates=new AtomicInteger();
        ModelClient model=new UpdatingModel(){
            public RequestBudgetReport inspectRequest(ModelRequest r){
                boolean candidate=r.verificationContext()!=null&&r.verificationContext().activeBases().stream()
                        .anyMatch(b->b.source().id().equals("turn-2-user"));
                return new RequestBudgetReport(candidate?65000:1000,4096,1024,65536);
            }
            public List<ActiveVerificationBasis> updateVerificationBasis(VerificationUpdateRequest r){
                updates.incrementAndGet();
                var result=new ArrayList<ActiveVerificationBasis>(super.updateVerificationBasis(r));
                r.sources().stream().filter(x->x.kind().equals("FILE")).findFirst().ifPresent(x->result.add(available(x)));
                return result;
            }
            public ModelResponse execute(ModelRequest r){
                if(r.history().isEmpty())return ModelResponse.callTool(new ToolCall("doc","read_file",
                        Map.of("path","contract.md","start_line","1","end_line","1","format","raw")));
                if(r.latestTurnId()==2)assertTrue(r.verificationContext().activeBases().stream()
                        .anyMatch(b->b.source().id().equals("doc")&&b.status().equals("AVAILABLE")));
                return ModelResponse.finish("done");
            }
        };
        var runtime=new AgentRuntime(model,List.of(new PinVerificationBasisTool(s,w),new ReadFileTool(w)),10);
        runtime.run(s);runtime.continueConversation(s,"保持规则，复核同一契约");
        assertEquals(AgentSession.Status.COMPLETED,s.status());assertEquals(3,updates.get());
        assertTrue(s.events().stream().anyMatch(e->e.type()==AgentSession.EventType.VERIFICATION_UPDATE_FAILED
                &&e.detail().contains("reasonCode=INSTALL_BUDGET")));
        assertTrue(s.verificationLedger().bases().stream().anyMatch(b->b.source().id().equals("doc")&&b.status().equals("AVAILABLE")));
    }
    @Test void obsoleteBasisDoesNotKeepItselfVisibleButSummaryAndToolArgumentsCountAsTraces() {
        var old=new VerificationSource("turn-1-user","USER",1,"仅统计5xx",null,"CURRENT");
        var current=new VerificationSource("turn-5-user","USER",5,"统计4xx和5xx，排除429",null,"CURRENT");
        var basis=new ActiveVerificationBasis(old,"仅统计5xx","SUPERSEDED","已替换",current,current.content());
        var ledger=new VerificationLedger(5,"1".repeat(64),List.of(basis));
        assertTrue(VerificationSources.project(ledger,List.of(current),Map.of(),Set.of()).isEmpty());
        var summary=new VerificationSource(old.id(),"DIALOGUE_SUMMARY",1,"旧策略仅使用服务器错误作为告警分子",null,"REFERENCE");
        assertTrue(VerificationSources.project(ledger,List.of(summary,current),Map.of(),Set.of()).isEmpty());
        var summaryTrace=new VerificationSource("turn-1-user-quote","TRACE_ONLY",0,"仅统计5xx",null,"REFERENCE");
        assertEquals(1,VerificationSources.project(ledger,List.of(summary,summaryTrace,current),Map.of(),Set.of()).size());
        var trace=new VerificationSource("patch-trace","TRACE_ONLY",0,"old_text=仅统计5xx",null,"REFERENCE");
        assertEquals(1,VerificationSources.project(ledger,List.of(trace,current),Map.of(),Set.of()).size());
    }
    @Test void changedFingerprintInvalidatesFileBasisAndHistoryCannotPromoteIt() {
        var file=new FileFingerprint("contract.md","a".repeat(64));
        var source=new VerificationSource("read","FILE",0,"429仍计入4xx桶",file,"CURRENT");
        var basis=available(source);var ledger=new VerificationLedger(1,"1".repeat(64),List.of(basis));
        var projected=VerificationSources.project(ledger,List.of(source),Map.of("contract.md","b".repeat(64)),Set.of());
        assertEquals("STALE",projected.getFirst().status());
        assertThrows(IllegalArgumentException.class,()->new VerificationUpdateRequest(1,List.of(),projected).validate(List.of(basis)));
        var history=new VerificationSource("old","HISTORY",0,source.content(),file,"REFERENCE");
        assertThrows(IllegalArgumentException.class,()->available(history));
    }
    @Test void quotesAndReplacementSourcesMustBeRealAndLater() {
        var source=new VerificationSource("turn-1-user","USER",1,"仅统计5xx",null,"CURRENT");
        assertThrows(IllegalArgumentException.class,()->new ActiveVerificationBasis(source,"虚构内容","AVAILABLE","",null,null));
        assertThrows(IllegalArgumentException.class,()->new ActiveVerificationBasis(source,source.content(),"SUPERSEDED","",source,source.content()));
        var foreign=new VerificationSource("turn-99-user","USER",99,"foreign",null,"CURRENT");
        assertThrows(IllegalArgumentException.class,()->new VerificationUpdateRequest(1,List.of(source),List.of()).validate(List.of(available(foreign))));
    }
    @Test void updateInputPrioritizesCurrentUserAndNeverFeedsTestLogsOrAllArchive() {
        var current=new VerificationSource("turn-5-user","USER",5,"429仍计入4xx桶",null,"CURRENT");
        var noise=new VerificationSource("test-trace","TRACE_ONLY",0,"[ERROR] "+"x".repeat(80000),null,"REFERENCE");
        assertEquals(List.of(current),VerificationSources.updateSources(List.of(noise,current),5,current.content(),List.of()));
    }
    @Test void updateInputExcludesImplementationTestsMemoryAndToolSummaries() {
        var current=new VerificationSource("turn-2-user","USER",2,"按docs/policy.md核验，不改pom",null,"CURRENT");
        var material=List.of(current,
                new VerificationSource("impl","FILE",0,"IMPLEMENTATION_ONLY",new FileFingerprint("RequestStats.java","a".repeat(64)),"CURRENT"),
                new VerificationSource("pom","FILE",0,"BUILD_XML_ONLY",new FileFingerprint("pom.xml","a".repeat(64)),"CURRENT"),
                new VerificationSource("memory","MEMORY",0,"MEMORY_ONLY",null,"REFERENCE"),
                new VerificationSource("summary","TOOL_SUMMARY",0,"TOOL_SUMMARY_ONLY",null,"REFERENCE"),
                new VerificationSource("turn-1-answer","ASSISTANT",1,"ASSISTANT_ANSWER_ONLY",null,"REFERENCE"),
                new VerificationSource("turn-1-user","DIALOGUE_SUMMARY",1,"历史要求摘要",null,"REFERENCE"));
        var selected=VerificationSources.updateSources(material,2,current.content(),List.of());
        var input=new VerificationUpdateRequest(2,selected,List.of()).referenceData();
        assertEquals(4,input.size());assertEquals(1,input.path("history").size());
        for(String marker:List.of("IMPLEMENTATION_ONLY","BUILD_XML_ONLY","MEMORY_ONLY","TOOL_SUMMARY_ONLY","ASSISTANT_ANSWER_ONLY"))
            assertFalse(input.toString().contains(marker));
    }
    @Test void successfulPatchAndCreationDoNotBecomeNormativeFileSources() throws Exception {
        var w=workspace();Files.writeString(root.resolve("contract.md"),"old normative rule\n");
        var s=new AgentSession("update policy");s.transitionTo(AgentSession.Status.RUNNING);s.transitionTo(AgentSession.Status.WAITING_FOR_TOOL);
        s.remember(new ToolExchange(new ToolCall("read","read_file",Map.of("path","contract.md","format","raw")),w.readFile("contract.md","1","1","raw")));
        var patch=Map.of("path","contract.md","old_text","old normative rule","new_text","PATCH_ONLY_NEW_RULE");
        var result=new ApplyPatchTool(w,s).execute(patch);assertTrue(result.successful());
        s.remember(new ToolExchange(new ToolCall("patch","apply_patch",patch),result));
        var create=Map.of("path","new-contract.md","content","CREATION_ONLY_RULE");
        var created=new CreateFileTool(w,s).execute(create);assertTrue(created.successful());
        s.remember(new ToolExchange(new ToolCall("create","create_file",create),created));
        var request=new ContextAssembler().assemble(s,List.of(),8);
        var visible=VerificationSources.visible(request);
        assertFalse(visible.stream().anyMatch(v->v.kind().equals("FILE")));
        var wire=new VerificationUpdateRequest(1,VerificationSources.updateSources(visible,1,s.objective(),List.of()),List.of()).referenceData();
        assertFalse(wire.toString().contains("PATCH_ONLY_NEW_RULE"));assertFalse(wire.toString().contains("CREATION_ONLY_RULE"));
    }
    @Test void wireOmitsFingerprintsAndPreservesCarriedRuleAndReplacementProvenance() {
        var user=new VerificationSource("turn-2-user","USER",2,"新规则排除429",null,"CURRENT");
        var old=new VerificationSource("turn-1-user","USER",1,"旧规则仅5xx",null,"CURRENT");
        var doc=new VerificationSource("read-doc","FILE",0,"当前契约约束",new FileFingerprint("docs/policy.md","d".repeat(64)),"CURRENT");
        var previous=new ActiveVerificationBasis(old,old.content(),"SUPERSEDED","",user,user.content());
        var request=new VerificationUpdateRequest(2,List.of(user,doc),List.of(previous));
        var view=request.referenceData();
        assertEquals(user.id(),view.path("currentTask").path("source").asText());
        assertEquals(user.id(),view.path("previousBasis").get(0).path("replacedBy").asText());
        assertEquals("docs/policy.md",view.path("contracts").get(0).path("path").asText());
        assertFalse(view.toString().contains("dddddddd"));assertFalse(view.toString().contains("currency"));
        assertFalse(view.toString().contains("reason"));assertEquals(List.of(previous),request.validate(List.of(previous)));
    }
    @Test void checkpointPersistsAttemptedFileVersionsAlongsideMatchingLedgerEvent() throws Exception {
        var repo=Files.createDirectory(root.resolve("repo-hashes"));Files.writeString(repo.resolve("contract.md"),"429仍计入4xx桶\n");
        var directory=root.resolve("hash-sessions");java.util.UUID id;
        try(var store=new dev.backendagent.persistence.FileSessionStore(directory)) {
            var w=new Workspace(repo,root.resolve("agent-local.properties"));store.bindWorkspace(w);
            var session=new AgentSession("核验contract.md",store);store.create(session);id=session.id();
            new AgentRuntime(new UpdatingModel(){
                public ModelResponse execute(ModelRequest r){return r.history().isEmpty()?
                        ModelResponse.callTool(new ToolCall("doc","read_file",Map.of("path","contract.md","start_line","1","end_line","1"))):ModelResponse.finish("done");}
            },List.of(new PinVerificationBasisTool(session,w),new ReadFileTool(w)),8).run(session);
            assertEquals(AgentSession.Status.COMPLETED,session.status());
        }
        try(var store=new dev.backendagent.persistence.FileSessionStore(directory)) {
            var w=new Workspace(repo,root.resolve("agent-local.properties"));var restored=store.openCompleted(id,w,12);
            assertEquals(Map.of("contract.md",w.fileFingerprint("contract.md").getSha256()),restored.verificationLedger().attemptedFileVersions());
        }
    }
    @Test void checkpointRestoresLedgerAndRejectsEventDisagreementWithoutLeakingAcrossSessions() throws Exception {
        var directory=root.resolve("sessions");var repo=Files.createDirectory(root.resolve("repo"));java.util.UUID id;
        try(var store=new dev.backendagent.persistence.FileSessionStore(directory)) {
            var w=new Workspace(repo,root.resolve("agent-local.properties"));store.bindWorkspace(w);var session=new AgentSession("429仍计入4xx桶",store);store.create(session);id=session.id();
            new AgentRuntime(new UpdatingModel(){public ModelResponse execute(ModelRequest r){return ModelResponse.finish("done");}},
                    List.of(new PinVerificationBasisTool(session,w)),5).run(session);
        }
        try(var store=new dev.backendagent.persistence.FileSessionStore(directory)) {
            var restored=store.openCompleted(id,new Workspace(repo,root.resolve("agent-local.properties")),10);
            assertEquals("429仍计入4xx桶",restored.verificationLedger().bases().getFirst().quote());
            assertNull(new AgentSession("different session").verificationLedger());
        }
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var checkpoint=directory.resolve(id.toString()).resolve("checkpoint.json");
        var saved=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readString(checkpoint));
        ((com.fasterxml.jackson.databind.node.ObjectNode)saved.path("verificationLedger")).put("attemptedDigest","f".repeat(64));
        Files.writeString(checkpoint,json.writeValueAsString(saved));
        try(var store=new dev.backendagent.persistence.FileSessionStore(directory)) {
            assertThrows(java.io.IOException.class,()->store.openCompleted(id,new Workspace(repo,root.resolve("agent-local.properties")),10));
        }
    }
}
