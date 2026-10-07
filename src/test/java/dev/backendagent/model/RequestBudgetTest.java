package dev.backendagent.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestBudgetTest {
    @Test
    void countsUtf8BytesIncludingMultibyteCharactersAndReservesOutputAndMargin() {
        var report = new RequestBudget(100, 20, 10).measure("a中😀");
        assertEquals(8, report.getRequestBytes());
        assertEquals(8, report.getEstimatedInputTokens());
        assertEquals(38, report.getEstimatedTotalTokens());
        assertTrue(report.isWithinBudget());
    }
    @Test
    void exactBudgetFitsAndOneExtraByteFails() {
        var budget = new RequestBudget(100, 20, 10);
        assertTrue(budget.measure("x".repeat(70)).isWithinBudget());
        assertFalse(budget.measure("x".repeat(71)).isWithinBudget());
    }
    @Test
    void invalidAndOverflowingConfigurationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RequestBudget(100, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new RequestBudget(100, 90, 10));
        assertThrows(IllegalArgumentException.class, () -> new RequestBudget(100, 20, -1));
        assertThrows(IllegalArgumentException.class, () -> new RequestBudget(Integer.MAX_VALUE, Integer.MAX_VALUE, 1));
        assertTrue(new RequestBudget(100, 20, 0).measure("x").isWithinBudget());
    }
}
