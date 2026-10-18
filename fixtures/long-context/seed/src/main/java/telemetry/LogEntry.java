package telemetry;
public final class LogEntry {
 public final long timestampMillis; public final String service; public final int status; public final long latencyMillis;
 public LogEntry(long timestampMillis,String service,int status,long latencyMillis) {this.timestampMillis=timestampMillis;this.service=service;this.status=status;this.latencyMillis=latencyMillis;}
}
