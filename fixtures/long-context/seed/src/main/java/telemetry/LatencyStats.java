package telemetry;
import java.util.List;
public final class LatencyStats {public static long percentile(List<LogEntry> e,int p) {e.sort(java.util.Comparator.comparingLong(x->x.latencyMillis));return e.get(e.size()*p/100).latencyMillis;}}
