package dev.backendagent.tools;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Set;

/** Tool bookkeeping only; never added to model context or used for source synchronization. */
public final class TurnDiffState {
    private final int turnId;
    private final boolean available;
    private final Map<String,String> originals;
    private final Set<String> created;

    @JsonCreator public TurnDiffState(@JsonProperty("turnId") int turnId,
            @JsonProperty("available") boolean available,
            @JsonProperty("originals") Map<String,String> originals,
            @JsonProperty("created") Set<String> created) {
        if (turnId < 0 || originals == null || created == null || originals.size() > 32
                || !originals.keySet().containsAll(created)
                || (!available && (!originals.isEmpty() || !created.isEmpty())))
            throw new IllegalArgumentException("Invalid turn diff state");
        for (var entry : originals.entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256*1024
                    || entry.getValue().indexOf('\0') >= 0 || (created.contains(entry.getKey()) && !entry.getValue().isEmpty()))
                throw new IllegalArgumentException("Invalid turn diff baseline");
        }
        this.turnId=turnId;this.available=available;this.originals=Map.copyOf(originals);this.created=Set.copyOf(created);
    }
    public static TurnDiffState empty(int turnId) { return new TurnDiffState(turnId,true,Map.of(),Set.of()); }
    public static TurnDiffState unavailable(int turnId) { return new TurnDiffState(turnId,false,Map.of(),Set.of()); }
    public int getTurnId(){return turnId;}
    public boolean isAvailable(){return available;}
    public Map<String,String> getOriginals(){return originals;}
    public Set<String> getCreated(){return created;}
}
