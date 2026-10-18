package telemetry;
import java.util.List;
public final class StatusMetrics {public static long[] summarize(List<LogEntry> e) {long[] r=new long[5];for(var x:e){r[0]++;if(x.status<300){r[1]++;r[4]+=x.latencyMillis;}if(x.status>400)r[2]++;if(x.status>500)r[3]++;}return r;}}
