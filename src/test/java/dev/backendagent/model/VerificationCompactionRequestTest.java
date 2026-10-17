package dev.backendagent.model;

import dev.backendagent.verification.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificationCompactionRequestTest {
    private VerificationSource source(String id,String text) {
        return new VerificationSource(id,"USER",1,text,null,"CURRENT");
    }
    private ActiveVerificationBasis basis(VerificationSource s,String quote) {
        return new ActiveVerificationBasis(s,quote,"AVAILABLE","",null,null);
    }
    @Test void entryCountIsUnlimitedWhenDisplayFits() {
        var s=source("turn-1-user", "abcdefghijklmnopqrstuvwxyz");
        var bases=new ArrayList<ActiveVerificationBasis>();
        for(char c='a';c<='z';c++)bases.add(basis(s,String.valueOf(c)));
        var r=new VerificationUpdateRequest(1,List.of(s),List.of());
        assertEquals(26,r.validate(bases).size());
        assertEquals(26,new VerificationLedger(1,r.digest(),bases).bases().size());
    }
    @Test void oversizedCandidateCanBeMergedButCannotInventEvidenceOrExceedTarget() {
        var s=source("turn-1-user","保留单位 边界条件 不可变接口 "+"原".repeat(600));
        var large=Collections.nCopies(12,basis(s,s.content()));
        var original=new VerificationUpdateRequest(1,List.of(s),List.of());
        assertTrue(VerificationLedger.displayCharacters(large)>6000);
        assertEquals(large,original.validateCandidate(large));
        assertThrows(IllegalArgumentException.class,()->original.validate(large));
        var r=new VerificationCompactionRequest(original,large,List.of(s));
        var result=List.of(basis(s,"保留单位 边界条件 不可变接口"));
        assertEquals(result,r.validate(result));
        assertThrows(IllegalArgumentException.class,()->r.validate(List.of()));
        var wide=new ArrayList<ActiveVerificationBasis>();
        for(int i=1;i<=9;i++)wide.add(basis(s,"原".repeat(500+i)));
        assertTrue(VerificationLedger.displayCharacters(wide)>3999);
        assertThrows(IllegalArgumentException.class,()->r.validate(wide));
        var hidden=source("turn-1-user","不可见规则");
        assertThrows(IllegalArgumentException.class,()->r.validate(List.of(basis(hidden,hidden.content()))));
        assertEquals(3999,r.referenceData().path("maxOutputCharacters").asInt());
    }
    private List<ActiveVerificationBasis> sized(VerificationSource s,int target) {
        var result=new ArrayList<ActiveVerificationBasis>();
        for(char c='a';c<='h';c++)result.add(basis(s,String.valueOf(c)));
        int extra=target-VerificationLedger.displayCharacters(result);
        for(int i=0;i<result.size()&&extra>0;i++) {
            int add=Math.min(699,extra);
            result.set(i,basis(s,String.valueOf((char)('a'+i)).repeat(1+add)));extra-=add;
        }
        assertEquals(0,extra);assertEquals(target,VerificationLedger.displayCharacters(result));
        return result;
    }
    @Test void exactCharacterBoundariesUseDisplayJsonAndStrictCompactionLimit() {
        var text=new StringBuilder();for(char c='a';c<='h';c++)text.append(String.valueOf(c).repeat(700)).append(' ');
        var s=source("turn-1-user",text.toString());var original=new VerificationUpdateRequest(1,List.of(s),List.of());
        assertEquals(6000,VerificationLedger.displayCharacters(original.validate(sized(s,6000))));
        assertThrows(IllegalArgumentException.class,()->original.validate(sized(s,6001)));
        var r=new VerificationCompactionRequest(original,sized(s,6001),List.of(s));
        assertEquals(3999,VerificationLedger.displayCharacters(r.validate(sized(s,3999))));
        assertThrows(IllegalArgumentException.class,()->r.validate(sized(s,4000)));
    }
    @Test void adjacentExcerptsMergeAndVisibleObsoleteWarningsSurvive() {
        var old=source("turn-1-user","仅统计5xx");
        var current=new VerificationSource("turn-2-user","USER",2,"统计4xx和5xx",null,"CURRENT");
        var doc=source("user-doc","单位毫秒 边界包含 "+"x".repeat(600));
        var warning=new ActiveVerificationBasis(old,old.content(),"SUPERSEDED","",current,current.content());
        var large=new ArrayList<>(Collections.nCopies(12,basis(doc,doc.content())));
        large.add(basis(doc,"单位毫秒"));large.add(basis(doc,"边界包含"));large.add(warning);
        var original=new VerificationUpdateRequest(2,List.of(old,current,doc),List.of());
        var r=new VerificationCompactionRequest(original,large,List.of(old,current,doc));
        var merged=basis(doc,"单位毫秒 边界包含");
        assertThrows(IllegalArgumentException.class,()->r.validate(List.of(merged)));
        assertEquals(2,r.validate(List.of(merged,warning)).size());
        var noTrace=new VerificationCompactionRequest(original,large,List.of(current,doc));
        assertEquals(List.of(merged),noTrace.validate(List.of(merged)));
    }
}
