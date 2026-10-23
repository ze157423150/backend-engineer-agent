package dev.backendagent.tools;

import java.util.Arrays;

/** Display-only bounded line comparison; separated changes have separate context hunks. */
final class FileDiff {
    private FileDiff() {}

    static String render(String path, String before, String after, int limit, boolean newFile) {
        String[] a=lines(before),b=lines(after);
        if(before.equals(after)|| (newFile&&after.isEmpty()) || (long)(a.length+1)*(b.length+1)>1_000_000)
            return renderSingle(path,before,after,limit,newFile);
        int[][] lcs=new int[a.length+1][b.length+1];
        for(int i=a.length-1;i>=0;i--)for(int j=b.length-1;j>=0;j--)
            lcs[i][j]=a[i].equals(b[j])?lcs[i+1][j+1]+1:Math.max(lcs[i+1][j],lcs[i][j+1]);
        var edits=new java.util.ArrayList<String>();
        int i=0,j=0;
        while(i<a.length||j<b.length){
            if(i<a.length&&j<b.length&&a[i].equals(b[j])){edits.add(" "+a[i++]);j++;}
            else if(i<a.length&&(j==b.length||lcs[i+1][j]>=lcs[i][j+1]))edits.add("-"+a[i++]);
            else edits.add("+"+b[j++]);
        }
        int[] oldPos=new int[edits.size()+1],newPos=new int[edits.size()+1];
        for(i=0;i<edits.size();i++){
            oldPos[i+1]=oldPos[i]+(edits.get(i).charAt(0)=='+'?0:1);
            newPos[i+1]=newPos[i]+(edits.get(i).charAt(0)=='-'?0:1);
        }
        var out=new StringBuilder("file="+path+"\n--- "+(newFile?"/dev/null":"before/"+path)+"\n+++ after/"+path+"\n");
        int scan=0;
        while(scan<edits.size()){
            while(scan<edits.size()&&edits.get(scan).charAt(0)==' ')scan++;
            if(scan==edits.size())break;
            int start=Math.max(0,scan-3),last=scan;
            for(i=scan+1;i<edits.size();i++){
                if(edits.get(i).charAt(0)!=' '){
                    if(i-last>7)break;
                    last=i;
                }
            }
            int end=Math.min(edits.size(),last+4),oc=oldPos[end]-oldPos[start],nc=newPos[end]-newPos[start];
            out.append("@@ -").append(oldPos[start]+(oc==0?0:1)).append(',').append(oc)
                .append(" +").append(newPos[start]+(nc==0?0:1)).append(',').append(nc).append(" @@\n");
            for(i=start;i<end;i++){
                if(out.length()+edits.get(i).length()+1>limit)
                    return out+"[差异已截断；后续变更块未完整展示，请按需读取目标范围]\n";
                out.append(edits.get(i)).append('\n');
            }
            scan=end;
        }
        out.append("[末尾换行：before=").append(before.endsWith("\n")).append(", after=").append(after.endsWith("\n")).append("]\n");
        return out.toString();
    }

    private static String renderSingle(String path, String before, String after, int limit, boolean newFile) {
        if (newFile && after.isEmpty()) { return "file=" + path + "\n[新增空文件]"; }
        if (before.equals(after)) {
            return "[相对本次首次修改前的快照，没有差异]";
        }
        String[] oldLines = lines(before);
        String[] newLines = lines(after);
        int prefix = 0;
        while (prefix < Math.min(oldLines.length, newLines.length)
                && oldLines[prefix].equals(newLines[prefix])) { prefix++; }
        int suffix = 0;
        while (suffix < Math.min(oldLines.length, newLines.length) - prefix
                && oldLines[oldLines.length - 1 - suffix].equals(newLines[newLines.length - 1 - suffix])) { suffix++; }
        int start = Math.max(0, prefix - 3);
        int oldEnd = Math.min(oldLines.length, oldLines.length - suffix + 3);
        int newEnd = Math.min(newLines.length, newLines.length - suffix + 3);
        var output = new StringBuilder("file=" + path + "\n--- " + (newFile ? "/dev/null" : "before/" + path)
                + "\n+++ after/" + path + "\n@@ -" + (oldEnd == start ? start : start + 1) + "," + (oldEnd - start)
                + " +" + (newEnd == start ? start : start + 1) + "," + (newEnd - start) + " @@\n");
        for (int i = start; i < prefix; i++) { output.append(' ').append(oldLines[i]).append('\n'); }
        for (int i = prefix; i < oldLines.length - suffix; i++) { output.append('-').append(oldLines[i]).append('\n'); }
        for (int i = prefix; i < newLines.length - suffix; i++) { output.append('+').append(newLines[i]).append('\n'); }
        for (int i = oldLines.length - suffix; i < oldEnd; i++) { output.append(' ').append(oldLines[i]).append('\n'); }
        output.append("[末尾换行：before=").append(before.endsWith("\n"))
                .append(", after=").append(after.endsWith("\n")).append("]\n");
        return output.length() <= limit ? output.toString()
                : output.substring(0, limit) + "\n[差异已截断，请 read_file 按行核查]\n";
    }

    private static String[] lines(String text) {
        if (text.isEmpty()) { return new String[0]; }
        String[] lines = text.split("\n", -1);
        return text.endsWith("\n") ? Arrays.copyOf(lines, lines.length - 1) : lines;
    }
}
