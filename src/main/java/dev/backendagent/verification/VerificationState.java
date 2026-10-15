package dev.backendagent.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.ToolExchange;
import java.util.*;

/** Session/turn-local cache reconstructed from successful registration tool results in the durable history. */
public final class VerificationState {
    public static final int MAX_BASES=8, MAX_BASIS_CHARACTERS=6000;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Map<String,VerificationBasis> bases=new LinkedHashMap<>();
    private final Map<String,String> validity=new HashMap<>();
    public List<VerificationBasis> bases(){return List.copyOf(bases.values());}
    public VerificationBasis basis(String id){return bases.get(id);}
    public String validity(String id){return validity.getOrDefault(id,"UNKNOWN");}
    public void checkFits(VerificationBasis value){var copy=new LinkedHashMap<>(bases);copy.put(value.getId(),value);
        if(copy.size()>MAX_BASES||length(copy.values())>MAX_BASIS_CHARACTERS)throw new IllegalArgumentException("Verification basis budget exceeded");}
    public void refresh(Map<String,String> hashes, Set<String> staleIds){
        bases.values().forEach(b->{String hash=hashes==null?null:hashes.get(b.getFile().getPath());
            validity.put(b.getId(),staleIds.contains(b.getSourceCallId())?"STALE":hashes==null?"UNKNOWN":
                b.getFile().getSha256().equals(hash)?"CURRENT":"STALE");});
    }
    public void observe(ToolExchange exchange, int turnId){
        if(!exchange.result().successful())return;
        if(dev.backendagent.runtime.WorkspaceState.isSuccessfulWrite(exchange)){
            String path=exchange.call().arguments().get("path");
            if(path!=null){
                var changed=java.nio.file.Path.of(path).normalize();
                bases.values().stream().filter(b->java.nio.file.Path.of(b.getFile().getPath()).normalize().equals(changed))
                        .forEach(b->validity.put(b.getId(),"STALE"));
            }
        }
        try {
            if(exchange.call().name().equals("pin_verification_basis")){
                var b=JSON.readValue(exchange.result().content(),VerificationBasis.class);
                if(b.getTurnId()!=turnId||!b.getId().equals(exchange.call().arguments().get("basis_id")))throw new IllegalArgumentException("Basis turn/id mismatch");
                checkFits(b);bases.put(b.getId(),b);validity.put(b.getId(),"UNKNOWN");

            }
        }catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid persisted verification registration",invalid);}
    }
    public static String encode(Object value){try{return JSON.writeValueAsString(value);}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
    private static int length(Object values){return encode(values).length();}
}
