package dev.backendagent.model;

import dev.backendagent.runtime.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SummaryGenerationEvaluationTest {
    @Test
    void fixedCasesExerciseDefaultGateAndFilterStaleReadsBeforeModelInput() {
        for (var test : SummaryGenerationEvaluation.cases()) {
            var session = SummaryEvaluationSession.seed(test.fixture.request.getObjective(), test.fixture.request.getObservations());
            var plan = new ContextCompactor(new ContextBudget(64000)).decide(session);
            assertEquals(test.expectSkip ? SummaryPlan.Reason.TOO_SHORT : SummaryPlan.Reason.READY, plan.getReason(), test.name);
            if (plan.getRequest() != null && test.name.equals("failed_test_long")) {
                assertTrue(plan.getRequest().getObservations().stream().noneMatch(x -> x.call().id().equals("read-price")));
                assertTrue(plan.getRequest().getObservations().stream().anyMatch(x -> x.call().id().equals("test-price")));
            }
        }
    }
}
