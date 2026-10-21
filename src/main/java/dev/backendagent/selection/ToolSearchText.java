package dev.backendagent.selection;

import java.util.*;
import java.util.regex.Pattern;
import dev.backendagent.tools.ToolDefinition;

final class ToolSearchText {
    private static final Pattern WORDS=Pattern.compile("[a-z][a-z0-9]*|[0-9]+|[\u4e00-\u9fff]+");
    private ToolSearchText() { }
    static List<String> terms(String value) {
        String normalized=value.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).replace('_',' ');
        var result=new ArrayList<String>();var matcher=WORDS.matcher(normalized);
        while(matcher.find()) {
            String word=matcher.group();
            if(word.charAt(0)>='\u4e00' && word.charAt(0)<='\u9fff') {
                for(int i=0;i+1<word.length();i++)result.add(word.substring(i,i+2));
            } else result.add(word);
        }
        return result;
    }
    static String text(ToolDefinition tool,boolean includeParameters) {
        var value=new StringBuilder(tool.getName()).append(' ').append(tool.getDescription());
        if(includeParameters)tool.getParameters().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e->value.append(' ').append(e.getKey()).append(' ').append(e.getValue()));
        return value.toString();
    }
    static boolean exactName(String query,ToolDefinition tool) {
        return query.toLowerCase(Locale.ROOT).contains(tool.getName());
    }
}
