package telemetry;
import java.util.List;
public final class LogAnalyzer {public static long[] analyze(List<String> lines) {var e=lines.stream().map(LogParser::parse).toList();var m=StatusMetrics.summarize(e);return new long[]{lines.size(),0,m[1],m[2],m[3],m[4],LatencyStats.percentile(e,95),AlertEvaluator.evaluate(e,3,2000)?1:0};}}
