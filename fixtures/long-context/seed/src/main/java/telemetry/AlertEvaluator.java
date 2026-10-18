package telemetry;
import java.util.List;
public final class AlertEvaluator {public static boolean evaluate(List<LogEntry> e,int min,int threshold) {long n=e.stream().filter(x->x.status>=500).count();return n/e.size()*10000>threshold;}}
