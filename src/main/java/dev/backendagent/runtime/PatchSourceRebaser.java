package dev.backendagent.runtime;

import dev.backendagent.model.FileFingerprint;
import dev.backendagent.model.ToolExchange;
import dev.backendagent.tools.ApplyPatchBatchTool;
import dev.backendagent.tools.PatchEdit;
import java.nio.file.Path;
import java.util.List;

/** Proves that a selected old fragment survived a complete chain of guarded managed patches. */
final class PatchSourceRebaser {
    private PatchSourceRebaser() { }

    static boolean canReuse(ToolExchange source,String oldText,FileFingerprint current,List<ToolExchange> history) {
        var expected=source.result().fileFingerprint();
        if(expected==null||current==null||!expected.getPath().equals(current.getPath()))return false;
        int index=-1;
        for(int i=0;i<history.size();i++)if(history.get(i).call().id().equals(source.call().id())){index=i;break;}
        if(index<0)return false;
        boolean advanced=false;
        try {
            for(int i=index+1;i<history.size();i++) {
                var next=history.get(i);
                if(!WorkspaceState.isSuccessfulWrite(next))continue;
                String path=next.call().arguments().get("path");
                if(path==null)return false;
                if(!Path.of(path).normalize().toString().equals(expected.getPath()))continue;
                // A missing link, legacy unguarded result, or unexpected baseline fails closed.
                if(!expected.equals(next.result().patchBaseFingerprint())||next.result().fileFingerprint()==null)return false;
                List<PatchEdit> edits=switch(next.call().name()) {
                    case "apply_patch" -> List.of(new PatchEdit("single",next.call().arguments().get("old_text"),next.call().arguments().get("new_text")));
                    case "apply_patch_batch" -> ApplyPatchBatchTool.decode(next.call().arguments().get("patches"));
                    default -> null;
                };
                if(edits==null)return false;
                // Without offsets for a partial observation, reject ANY possible overlap, even at
                // either boundary. The workspace still enforces a unique match at the new baseline.
                for(var edit:edits)if(possibleOverlap(oldText,edit.getOldText()))return false;
                expected=next.result().fileFingerprint();advanced=true;
            }
            return advanced&&expected.equals(current);
        } catch(java.io.IOException|IllegalArgumentException invalid) { return false; }
    }

    static boolean possibleOverlap(String a,String b) {
        return a.contains(b)||b.contains(a)||suffixPrefix(a,b)>0||suffixPrefix(b,a)>0;
    }
    /** Linear-time longest suffix of left matching a prefix of right. */
    private static int suffixPrefix(String left,String right) {
        int[] prefix=new int[right.length()];
        for(int i=1,j=0;i<right.length();i++) {
            while(j>0&&right.charAt(i)!=right.charAt(j))j=prefix[j-1];
            if(right.charAt(i)==right.charAt(j))j++;
            prefix[i]=j;
        }
        int j=0;
        for(int i=0;i<left.length();i++) {
            if(j==right.length())j=prefix[j-1];
            while(j>0&&left.charAt(i)!=right.charAt(j))j=prefix[j-1];
            if(left.charAt(i)==right.charAt(j))j++;
        }
        return j;
    }
}
