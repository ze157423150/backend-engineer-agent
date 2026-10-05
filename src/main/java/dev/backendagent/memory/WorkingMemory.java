package dev.backendagent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded memory for one session; identical statements are refreshed, oldest entries are evicted. */
public final class WorkingMemory {
    public static final int MAX_FACTS = 8;
    public static final int MAX_CHARACTERS = 4000;
    private final List<MemoryFact> facts = new ArrayList<>();

    public void save(MemoryFact fact) {
        Objects.requireNonNull(fact);
        facts.removeIf(existing -> existing.getStatement().equals(fact.getStatement()));
        facts.add(fact);
        while (facts.size() > MAX_FACTS || characterCount() > MAX_CHARACTERS) {
            facts.removeFirst();
        }
    }

    public List<MemoryFact> snapshot() { return List.copyOf(facts); }
    public void removeSources(java.util.Set<String> callIds) {
        facts.removeIf(fact -> callIds.contains(fact.getSourceCallId()));
    }
    public int characterCount() { return facts.stream().mapToInt(MemoryFact::characterCount).sum(); }
}
