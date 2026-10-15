package dev.backendagent.runtime;

import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import java.nio.file.Path;
import java.util.*;

/** Only combines adjacent single-patch calls; never reorders other tools or crosses files. */
public final class PatchBatchNormalizer {
    private PatchBatchNormalizer() {}
    public static ModelResponse normalize(ModelResponse response){
        if(response.getType()!=ModelResponse.Type.TOOL_CALL)return response;
        var input=response.getToolCalls();var output=new ArrayList<ToolCall>();boolean changed=false;
        for(int i=0;i<input.size();){
            var first=input.get(i);int end=i+1;String path=path(first);
            if(path!=null)while(end<input.size()&&path.equals(path(input.get(end))))end++;
            if(end-i==1){output.add(first);i++;continue;}
            try{
                var edits=new ArrayList<PatchEdit>();var ids=new ArrayList<String>();
                for(int j=i;j<end;j++){var c=input.get(j);edits.add(new PatchEdit(c.id(),c.arguments().get("old_text"),c.arguments().get("new_text")));ids.add(c.id());}
                String patches=ApplyPatchBatchTool.encode(edits);
                var args=new LinkedHashMap<String,String>();args.put("path",path);args.put("patches",patches);
                // Retain original IDs in the durable arguments for audit and duplicate-ID checks.
                args.put("source_call_ids",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ids));
                output.add(new ToolCall(first.id(),"apply_patch_batch",args));changed=true;i=end;
            }catch(java.io.IOException|IllegalArgumentException invalid){output.add(first);i++;}
        }
        return changed?ModelResponse.callTools(output,response.getAssistantContent()):response;
    }
    private static String path(ToolCall call){
        if(!call.name().equals("apply_patch"))return null;
        try{String p=call.arguments().get("path");return p==null?null:Path.of(p).normalize().toString();}catch(IllegalArgumentException invalid){return null;}
    }
    public static Set<String> ids(ToolCall call){
        var ids=new HashSet<String>();ids.add(call.id());
        if(call.name().equals("apply_patch_batch")&&call.arguments().containsKey("source_call_ids")){
            try{ids.addAll(new com.fasterxml.jackson.databind.ObjectMapper().readValue(call.arguments().get("source_call_ids"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}));}
            catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid normalized patch IDs",invalid);}
        }
        return ids;
    }
}
