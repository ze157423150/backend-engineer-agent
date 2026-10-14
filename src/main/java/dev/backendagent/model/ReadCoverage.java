package dev.backendagent.model;

import java.util.*;

/** Union of observed source ranges keyed by full file bytes, independent of display format. */
public final class ReadCoverage {
    private final Map<FileFingerprint,List<int[]>> ranges=new HashMap<>();
    public boolean observe(FileFingerprint file,FileReadMetadata read){
        var list=ranges.computeIfAbsent(file,k->new ArrayList<>());
        boolean covered=list.stream().anyMatch(r->r[0]<=read.getStartLine()&&r[1]>=read.getEndLine());
        list.add(new int[]{read.getStartLine(),read.getEndLine()});list.sort(Comparator.comparingInt(r->r[0]));
        var merged=new ArrayList<int[]>();
        for(var range:list){
            if(merged.isEmpty()||(long)merged.getLast()[1]+1<range[0])merged.add(range.clone());
            else merged.getLast()[1]=Math.max(merged.getLast()[1],range[1]);
        }
        ranges.put(file,merged);return !covered;
    }
    public void clear(){ranges.clear();}
}
