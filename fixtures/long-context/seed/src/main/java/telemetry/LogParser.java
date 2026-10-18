package telemetry;
public final class LogParser {public static LogEntry parse(String line) {String[] p=line.split("\\|");return new LogEntry(Long.parseLong(p[0]),p[1],Integer.parseInt(p[2]),Long.parseLong(p[3]));}}
