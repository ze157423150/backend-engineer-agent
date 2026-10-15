package dev.backendagent.runtime;

import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import dev.backendagent.verification.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VerificationWorkflowTest {
    @TempDir Path root;
    private Workspace workspace()throws Exception{return new Workspace(root,root.resolve("agent-local.properties"));}
    private AgentSession pin(Workspace w)throws Exception{
        Files.writeString(root.resolve("tax.md"),"1000 basis points = 10%\nTax is (base*rate+5000)/10000\n");
        var s=new AgentSession("repair");s.transitionTo(AgentSession.Status.RUNNING);s.transitionTo(AgentSession.Status.WAITING_FOR_TOOL);
        var read=w.readFile("tax.md","1","2","raw");s.remember(new ToolExchange(new ToolCall("read","read_file",Map.of("path","tax.md","format","raw")),read));
        var args=Map.of("basis_id","TAX","source_call_id","read","start_line","1","end_line","2");
        var result=new PinVerificationBasisTool(s,w).execute(args);assertTrue(result.successful(),result.content());
        s.remember(new ToolExchange(new ToolCall("pin","pin_verification_basis",args),result));s.refreshVerificationBasis();return s;
    }
    @Test void externalEditInvalidatesPinnedBasis()throws Exception{
        var w=workspace();var s=pin(w);
        Files.writeString(root.resolve("tax.md"),"new policy\n");s.refreshVerificationBasis();
        assertEquals("STALE",s.verificationState().validity("TAX"));
        assertTrue(s.verificationContext().getBases().getFirst().getContent().contains("BODY_OMITTED"));
    }
    @Test void managedWriteImmediatelyInvalidatesTheSnapshotView()throws Exception{
        var w=workspace();var s=pin(w);assertEquals("CURRENT",s.verificationState().validity("TAX"));
        var args=Map.of("path","tax.md","old_text","10%","new_text","12%");
        var result=new ApplyPatchTool(w,s).execute(args);assertTrue(result.successful());
        s.remember(new ToolExchange(new ToolCall("write","apply_patch",args),result));
        assertEquals("STALE",s.verificationState().validity("TAX"));
        assertTrue(s.verificationContext().getBases().getFirst().getContent().contains("BODY_OMITTED"));
        var filtered=StaleObservationFilter.filter(s.history());
        assertTrue(filtered.stream().filter(e->e.call().id().equals("pin")).findFirst().orElseThrow()
                .result().content().contains("STALE_OBSERVATION_BODY_OMITTED"));
    }
    @Test void sourceRangeMustHaveActuallyBeenReadAndForeignSessionCannotPinIt()throws Exception{
        var w=workspace();var s=pin(w);var args=Map.of("basis_id","OTHER","source_call_id","read","start_line","3","end_line","3");
        assertFalse(new PinVerificationBasisTool(s,w).execute(args).successful());
        var foreign=new AgentSession("other");foreign.transitionTo(AgentSession.Status.RUNNING);foreign.transitionTo(AgentSession.Status.WAITING_FOR_TOOL);
        assertFalse(new PinVerificationBasisTool(foreign,w).execute(Map.of("basis_id","TAX","source_call_id","read","start_line","1","end_line","2")).successful());
    }
    @Test void basisSurvivesOrdinaryWindowAndIsIncludedInBudget()throws Exception{
        var w=workspace();var s=pin(w);
        for(int i=0;i<25;i++)s.remember(new ToolExchange(new ToolCall("r"+i,"list_files",Map.of()),new ToolResult(true,"entry"),null,i+1));
        var request=new ContextAssembler().assemble(s,List.of(),10);
        assertFalse(request.history().stream().anyMatch(e->e.call().id().equals("read")));
        assertEquals("CURRENT",request.verificationContext().getBases().getFirst().getValidity());
        assertTrue(request.historyCharacters()>=request.verificationContext().characterCount());
        assertThrows(IllegalStateException.class,()->new ContextAssembler(new ContextBudget(100)).assemble(s,List.of(),10));
    }
    @Test void newUserTurnDoesNotSilentlyCarryOldRequirements()throws Exception{
        var w=workspace();var s=pin(w);s.transitionTo(AgentSession.Status.RUNNING);s.finish("done");s.transitionTo(AgentSession.Status.COMPLETED);
        s.continueWith("change the rate");assertTrue(s.verificationState().bases().isEmpty());assertEquals(0,s.completionRejections());
    }
    @Test void identicalRepinRetainsSourceButReplacementHidesOldPinnedHistory()throws Exception{
        var w=workspace();var s=pin(w);
        var same=Map.of("basis_id","TAX","source_call_id","read","start_line","01","end_line","02");
        s.remember(new ToolExchange(new ToolCall("repin","pin_verification_basis",same),new PinVerificationBasisTool(s,w).execute(same)));
        s.refreshVerificationBasis();assertEquals("CURRENT",s.verificationState().validity("TAX"));
        assertFalse(StaleObservationFilter.staleIds(s.history()).contains("pin"));
        var changed=Map.of("basis_id","TAX","source_call_id","read","start_line","2","end_line","2");
        s.remember(new ToolExchange(new ToolCall("replace","pin_verification_basis",changed),new PinVerificationBasisTool(s,w).execute(changed)));
        s.refreshVerificationBasis();assertEquals(2,s.verificationState().bases().getFirst().getStartLine());
        assertTrue(StaleObservationFilter.staleIds(s.history()).containsAll(Set.of("pin","repin")));
    }
    @Test void testDiagnosticsRemainAvailableWithoutExpectationRegistration(){
        var report=TestFailureReport.from("t",0,false,"testExecution=EXECUTED\ntestsExecuted=2\n[ERROR] TaxTest.amount:12 expected: <180> but was: <1800>\n[ERROR] UnknownTest.amount:9 failure");
        assertEquals("TaxTest.amount",report.getFailures().getFirst().getMethod());
        assertEquals("180",report.getFailures().getFirst().getExpected());assertEquals("1800",report.getFailures().getFirst().getActual());
    }
    @Test void legacyExpectationResultsDoNotRebuildAnActiveRegistration()throws Exception{
        var w=workspace();var s=pin(w);
        s.remember(new ToolExchange(new ToolCall("legacy","register_test_expectation",Map.of("case_id","old")),new ToolResult(true,"{invalid legacy payload")));
        var json=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(s.verificationContext());
        assertFalse(json.has("expectations"));assertEquals(1,s.verificationState().bases().size());
    }
}
