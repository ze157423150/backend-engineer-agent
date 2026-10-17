package dev.backendagent.runtime;

import dev.backendagent.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingFailureSummaryTest {
    private static final String DIFFERENCE="index [2], expected: <4> but was: <3>";
    private ToolExchange read(int n,int length) {
        return new ToolExchange(new ToolCall("r"+n,"read_file",Map.of("path","Main.java")),
                new ToolResult(true,"evidence "+n+"\n"+"x".repeat(length)),null,n);
    }
    private ToolExchange test(String id,int batch,boolean passed) {
        return new ToolExchange(new ToolCall(id,"run_tests",Map.of()),new ToolResult(passed,
                "workspaceRevision=0\nstatus="+(passed?"PASSED":"FAILED")+"\nexitCode="+(passed?0:1)
                +"\ntestExecution=EXECUTED\n"+(passed?"[INFO] Tests run: 1, Failures: 0":"[ERROR] caseRound45: "+DIFFERENCE)),null,batch);
    }
    private AgentSession skipped() {
        var s=new AgentSession("inspect");s.remember(test("failure",1,false));
        for(int n=2;n<=8;n++)s.remember(read(n,0));
        var plan=new ContextCompactor(new ContextBudget(64000)).decide(s);
        assertEquals(SummaryPlan.Reason.TOO_SHORT,plan.getReason());
        s.append(AgentSession.EventType.WINDOW_SUMMARY_SKIPPED,""+plan.getProcessedEnd(),plan);
        return s;
    }
    private void grow(AgentSession s) { for(int n=9;n<=24;n++)s.remember(read(n,700)); }
    private SummaryNote failureNote() {
        return new SummaryNote(SummaryNote.Kind.OPEN_ISSUE,"caseRound45: 2xx expected4/actual3，待核对", "failure",DIFFERENCE,SummarySection.OPEN_ISSUES);
    }
    @Test void skippedFailureIsRetrievedOutsideWindowAndAcceptedWithOriginalQuote() {
        var s=skipped();grow(s);var compactor=new ContextCompactor(new ContextBudget(64000));
        var request=compactor.plan(s);assertNotNull(request);
        assertTrue(ContextWindowPolicy.suffixStart(s.history(),12)>0);
        assertTrue(request.getObservations().stream().anyMatch(e->e.call().id().equals("failure")&&e.result().content().contains(DIFFERENCE)));
        assertTrue(request.getObservations().stream().anyMatch(e->e.call().name().equals("read_file")));
        var summary=compactor.validate(request,List.of(failureNote()),s);
        assertNotNull(summary);
        assertTrue(request.referenceData().toString().length()<=ContextCompactor.MAX_INPUT_CHARACTERS);
    }
    @Test void rejectedOrOmittedFailureRemainsPendingAtNextSummaryOpportunity() {
        var s=skipped();grow(s);var compactor=new ContextCompactor(new ContextBudget(64000));
        var first=compactor.plan(s);var read=first.getObservations().stream().filter(e->e.call().name().equals("read_file")).findFirst().orElseThrow();
        var irrelevant=new SummaryNote(SummaryNote.Kind.PROGRESS,"historical read",read.call().id(),"evidence");
        assertThrows(IllegalArgumentException.class,()->compactor.validate(first,List.of(irrelevant),s));
        s.append(AgentSession.EventType.WINDOW_SUMMARY_ATTEMPTED,""+first.getToIndex());
        assertNull(compactor.plan(s));
        for(int n=25;n<=28;n++)s.remember(read(n,700));
        var next=compactor.plan(s);assertNotNull(next);
        assertTrue(next.getObservations().stream().anyMatch(e->e.call().id().equals("failure")));
        assertNotNull(compactor.validate(next,List.of(failureNote()),s));
    }
    @Test void installedFailureIsCarriedWithoutDuplicatingRawInputAndSuccessRetiresIt() {
        var s=skipped();grow(s);var compactor=new ContextCompactor(new ContextBudget(64000));
        var first=compactor.plan(s);s.installSummary(compactor.validate(first,List.of(failureNote()),s));
        for(int n=25;n<=28;n++)s.remember(read(n,700));
        var next=compactor.plan(s);assertNotNull(next);
        assertTrue(next.getObservations().stream().noneMatch(e->e.call().id().equals("failure")));
        assertEquals("failure",next.getPreviousSummary().getNotes().getFirst().getSourceCallId());
        s.remember(test("passed",29,true));
        assertNull(ContextWindowPolicy.visibleSummary(s.contextSummary(),s.history(),StaleObservationFilter.staleIds(s.history())));
        assertEquals(-1,SummarySourcePolicy.pendingTestFailure(s.history(),StaleObservationFilter.staleIds(s.history())));
    }
    @Test void writesStillInvalidateOldTestEvidenceAndNewFailureSupersedesOldOne() {
        var s=skipped();grow(s);
        s.remember(new ToolExchange(new ToolCall("patch","apply_patch",Map.of("path","Main.java")),new ToolResult(true,"modified"),null,25));
        var stale=StaleObservationFilter.staleIds(s.history());assertTrue(stale.contains("failure"));
        assertEquals(-1,SummarySourcePolicy.pendingTestFailure(s.history(),stale));
        s.remember(test("newFailure",26,false));
        assertEquals(s.history().size()-1,SummarySourcePolicy.pendingTestFailure(s.history(),StaleObservationFilter.staleIds(s.history())));
    }
    @Test void unrelatedDistantSourcesStillCannotEnterSummary() {
        var s=skipped();grow(s);var compactor=new ContextCompactor(new ContextBudget(64000));var request=compactor.plan(s);
        var invented=new SummaryNote(SummaryNote.Kind.PROGRESS,"old read","r2","evidence 2");
        assertThrows(IllegalArgumentException.class,()->compactor.validate(request,List.of(failureNote(),invented),s));
    }
}
