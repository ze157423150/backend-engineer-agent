package dev.backendagent.tools;

import java.util.*;

/** Validates all locations before constructing any changed content. */
public final class PatchPlan {
    private record Range(PatchEdit edit, int start, int end) {}
    private PatchPlan() {}
    public static String apply(String before, List<PatchEdit> edits) {
        if (edits == null || edits.isEmpty() || edits.size() > 16) throw new IllegalArgumentException("PATCH_BATCH_LIMIT: 一组必须有1..16个补丁；本组未写入。");
        var ranges=new ArrayList<Range>(); var ids=new HashSet<String>();
        for(var edit:edits){
            if(!ids.add(edit.getId()))throw new IllegalArgumentException("PATCH_DUPLICATE_ID: "+edit.getId()+"；本组未写入，请使用不同ID。");
            if(edit.getOldText().equals(edit.getNewText()))throw new IllegalArgumentException("PATCH_NO_CHANGE: "+edit.getId()+"新旧文本相同；本组未写入。");
            int start=before.indexOf(edit.getOldText());
            if(start<0){
                String hint=before.replaceAll("(?m)^[ \\t]+", "").contains(edit.getOldText().replaceAll("(?m)^[ \\t]+", ""))?"疑似缩进不一致；":"";
                throw new IllegalArgumentException(hint+"PATCH_BASELINE_MISSING: 补丁 "+edit.getId()+" 的old_text在共同基线中不存在；本组全部未写入。所有补丁须基于同一原文，不能依赖前一补丁生成的新文本；请read_file读取后合并或分轮提交。");
            }
            if(before.indexOf(edit.getOldText(),start+1)>=0)throw new IllegalArgumentException("PATCH_AMBIGUOUS: 补丁 "+edit.getId()+" 的旧文本匹配多处；本组全部未写入，请增加定位上下文。");
            ranges.add(new Range(edit,start,start+edit.getOldText().length()));
        }
        ranges.sort(Comparator.comparingInt(Range::start));
        for(int i=1;i<ranges.size();i++){
            var a=ranges.get(i-1);var b=ranges.get(i);
            if(b.start()<a.end())throw new IllegalArgumentException("PATCH_OVERLAP: 补丁 "+a.edit().getId()+" [字符"+a.start()+","+a.end()+") 与 "+b.edit().getId()+" [字符"+b.start()+","+b.end()+") 在共同基线中重叠；本组全部未写入。请合并成一个补丁，或去除重复修改后重新提交；位置为原文UTF-16字符偏移，不是行号。");
        }
        var after=new StringBuilder(before);
        for(int i=ranges.size()-1;i>=0;i--){var range=ranges.get(i);after.replace(range.start(),range.end(),range.edit().getNewText());}
        return after.toString();
    }
}
