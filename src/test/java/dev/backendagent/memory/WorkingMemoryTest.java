package dev.backendagent.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkingMemoryTest {
    @Test
    void refreshesSameStatementAndPreservesImmutableSnapshots() {
        var memory = new WorkingMemory();
        memory.save(fact("entry", "old", "code", "Main.java"));
        var snapshot = memory.snapshot();
        memory.save(fact("entry", "new", "new code", "Main.java"));
        assertEquals(1, memory.snapshot().size());
        assertEquals("new", memory.snapshot().getFirst().getSourceCallId());
        assertEquals("old", snapshot.getFirst().getSourceCallId());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
    }

    @Test
    void evictsOldestEntryAtCountLimit() {
        var memory = new WorkingMemory();
        for (int i = 0; i < 9; i++) {
            memory.save(fact("entry-" + i, "id", "code", "Main.java"));
        }
        assertEquals(8, memory.snapshot().size());
        assertEquals("entry-1", memory.snapshot().getFirst().getStatement());
    }

    @Test
    void enforcesTextBudgetEvenBeforeCountLimit() {
        var memory = new WorkingMemory();
        for (int i = 0; i < 5; i++) {
            memory.save(fact("s".repeat(299) + i, "id", "q".repeat(400), "p".repeat(500)));
        }
        assertTrue(memory.characterCount() <= 4000);
        assertEquals(3, memory.snapshot().size());
        assertTrue(memory.snapshot().getFirst().getStatement().endsWith("2"));
    }

    @Test
    void rejectsBlankAndOversizedFields() {
        assertThrows(IllegalArgumentException.class, () -> fact(" ", "id", "quote", "Main.java"));
        assertThrows(IllegalArgumentException.class, () -> fact("s".repeat(301), "id", "quote", "Main.java"));
        assertThrows(IllegalArgumentException.class, () -> fact("note", "id", "q".repeat(401), "Main.java"));
    }

    private MemoryFact fact(String statement, String id, String quote, String path) {
        return new MemoryFact(statement, id, "read_file", path, quote);
    }
}
