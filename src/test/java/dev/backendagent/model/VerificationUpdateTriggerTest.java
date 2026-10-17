package dev.backendagent.model;

import dev.backendagent.verification.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificationUpdateTriggerTest {
    private VerificationSource user(int turn) {return new VerificationSource("turn-"+turn+"-user","USER",turn,"核验规范",null,"CURRENT");}
    private VerificationSource file(String id,String path,String sha,String body) {
        return new VerificationSource(id,"FILE",0,body,new FileFingerprint(path,sha.repeat(64)),"CURRENT");
    }
    @Test void triggerTracksFileVersionsNotCallIdsFormattingVisibilityOrSourceOrder() {
        var first=new VerificationUpdateRequest(1,List.of(user(1),file("r1","a.md","a","1: 规则")),List.of());
        var tracked=first.fileVersions(Map.of());var key=first.digest(tracked);
        var repeated=new VerificationUpdateRequest(1,List.of(file("r2","a.md","a","规则"),user(1)),List.of());
        assertEquals(key,repeated.digest(repeated.fileVersions(tracked)));
        var absent=new VerificationUpdateRequest(1,List.of(user(1)),List.of());
        assertEquals(key,absent.digest(absent.fileVersions(tracked)));
        var fresh=new VerificationUpdateRequest(1,List.of(user(1),file("r3","a.md","b","新规则")),List.of());
        assertNotEquals(key,fresh.digest(fresh.fileVersions(tracked)));
        var extra=new VerificationUpdateRequest(1,List.of(user(1),file("r4","b.md","a","规则")),List.of());
        assertNotEquals(key,extra.digest(extra.fileVersions(tracked)));
        assertNotEquals(key,new VerificationUpdateRequest(2,List.of(user(2)),List.of()).digest(Map.of()));
    }
    @Test void versionMemoryRoundTripsAndOlderJsonRemainsCompatible() throws Exception {
        var json=new ObjectMapper();var r=new VerificationUpdateRequest(1,List.of(user(1)),List.of());
        var ledger=new VerificationLedger(1,r.digest(),List.of(),Map.of("a.md","a".repeat(64)));
        assertEquals(ledger,json.readValue(json.writeValueAsString(ledger),VerificationLedger.class));
        String old="{\"turnId\":1,\"attemptedDigest\":\""+r.digest()+"\",\"bases\":[]}";
        var restored=json.readValue(old,VerificationLedger.class);
        assertTrue(restored.attemptedFileVersions().isEmpty());
        assertEquals(json.readTree(old),json.valueToTree(restored));
    }
}
