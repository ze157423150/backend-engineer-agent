package dev.backendagent.ptc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.backendagent.persistence.PersistenceException;
import dev.backendagent.sandbox.LocalProcessRunner;

/** Fresh, networkless Python container. No repository, session directory or Docker socket is mounted. */
public final class DockerPtcExecutor implements PtcExecutor {
    public static final String DEFAULT_IMAGE = "backend-agent-ptc-runtime:local";
    public static final int MAX_OUTPUT_CHARS = 24000;
    private static final int MAX_FRAME_BYTES = 256 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String image;
    private final Duration timeout;

    public DockerPtcExecutor(String image) { this(image, Duration.ofSeconds(180)); }
    public DockerPtcExecutor(String image, Duration timeout) {
        if (image == null || !image.matches("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,200}")
                || timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(900)) > 0)
            throw new IllegalArgumentException("Invalid PTC image or timeout");
        this.image = image; this.timeout = timeout;
    }

    List<String> command(String name) {
        return List.of("docker", "run", "--rm", "--pull=never", "--name", name, "--interactive",
                "--network", "none", "--log-driver", "none", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--pids-limit", "32", "--memory", "192m", "--memory-swap", "192m", "--cpus", "1",
                "--user", "65534:65534", "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=16m,uid=65534,gid=65534",
                "--workdir", "/tmp", "--entrypoint", "python3", image, "-I", "-u", "/opt/ptc/runtime.py");
    }

    @Override public PtcRunResult run(String code, List<String> bindings, Dispatcher dispatcher) {
        return runProgram(code, bindings, List.of(), dispatcher);
    }

    @Override public PtcRunResult runWithDefinitions(String code, List<dev.backendagent.tools.ToolDefinition> definitions, Dispatcher dispatcher) {
        return runProgram(code, definitions.stream().map(dev.backendagent.tools.ToolDefinition::getName).toList(), definitions, dispatcher);
    }

    private PtcRunResult runProgram(String code, List<String> bindings,
                                  List<dev.backendagent.tools.ToolDefinition> definitions, Dispatcher dispatcher) {
        String name = "backend-ptc-" + UUID.randomUUID();
        Process process = null;
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ptc-deadline"); t.setDaemon(true); return t;
        });
        var timedOut = new AtomicBoolean();
        try {
            process = new ProcessBuilder(command(name)).start();
            Process running = process;
            // Drain stderr separately: untrusted diagnostics cannot become control messages.
            Thread.ofPlatform().daemon(true).start(() -> {
                try (var stream = running.getErrorStream()) { stream.transferTo(OutputStream.nullOutputStream()); }
                catch (IOException ignored) { }
            });
            timer.schedule(() -> {
                timedOut.set(true); running.destroyForcibly();
                try { remove(name); } catch (RuntimeException ignored) { /* final cleanup is authoritative */ }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            var input = new BufferedInputStream(process.getInputStream());
            var output = new BufferedOutputStream(process.getOutputStream());
            var initial = JSON.createObjectNode().put("code", code);
            initial.set("bindings", JSON.valueToTree(bindings)); initial.put("maxOutputChars", MAX_OUTPUT_CHARS);
            var contracts = initial.putObject("contracts");
            for (var definition : definitions) {
                var contract = contracts.putObject(definition.getName());
                contract.set("parameters", JSON.valueToTree(definition.getParameters().keySet().stream().sorted().toList()));
                contract.set("required", JSON.valueToTree(definition.getRequiredParameters().stream().sorted().toList()));
            }
            send(output, initial);
            for (int frames = 0; frames < 34; frames++) {
                JsonNode frame = read(input);
                if (frame == null) return failure(timedOut.get() ? "PTC_TIMEOUT" : "PTC_WORKER_EXIT: check the runtime image");
                if (timedOut.get()) return failure("PTC_TIMEOUT");
                String type = frame.path("type").asText();
                if (type.equals("done")) {
                    if (!frame.path("logs").isArray() || !(frame.path("error").isNull() || frame.path("error").isTextual()))
                        return failure("PTC_PROTOCOL_ERROR: invalid outcome");
                    var logs = new ArrayList<String>();
                    for (var line : frame.path("logs")) {
                        if (!line.isTextual()) return failure("PTC_PROTOCOL_ERROR: invalid logs");
                        logs.add(line.asText());
                    }
                    if (frame.toString().length() > MAX_OUTPUT_CHARS + 256) return failure("PTC_OUTPUT_LIMIT");
                    return new PtcRunResult(frame.get("value"), logs, frame.path("error").isNull() ? null : frame.path("error").asText());
                }
                if (!type.equals("call") || !frame.path("id").isIntegralNumber()
                        || frame.path("id").asInt() != frames + 1 || !frame.path("tool").isTextual()
                        || frame.path("tool").asText().length() > 100 || !frame.path("arguments").isObject())
                    return failure("PTC_PROTOCOL_ERROR: invalid binding call");
                var reply = JSON.createObjectNode().put("id", frames + 1);
                try {
                    var args = new LinkedHashMap<String, String>();
                    var fields = frame.path("arguments").fields();
                    while (fields.hasNext()) {
                        var field = fields.next(); var value = field.getValue();
                        if (frame.path("tool").asText().equals("apply_patch_batch")
                                && field.getKey().equals("patches") && value.isArray())
                            throw new IllegalArgumentException("PTC_INVALID_TOOL_ARGUMENTS: apply_patch_batch.patches要求JSON数组字符串，不是Python列表；"
                                    + "使用json.dumps(edits, ensure_ascii=False)。本次工具未执行；若原文仍有效，仅修正序列化，无需重复读取。");
                        if (args.size() >= 16 || field.getKey().length() > 100
                                || !(value.isTextual() || value.isIntegralNumber()) || value.asText().length() > 24000)
                            throw new IllegalArgumentException("PTC_INVALID_TOOL_ARGUMENTS: string or integer scalar parameters only");
                        args.put(field.getKey(), value.asText());
                    }
                    reply.put("ok", true).set("value", dispatcher.call(frame.path("tool").asText(), Map.copyOf(args)));
                } catch (PersistenceException fatal) { throw fatal; }
                catch (RuntimeException failed) { reply.put("ok", false).put("error", String.valueOf(failed.getMessage())); }
                send(output, reply);
            }
            return failure("PTC_PROTOCOL_LIMIT");
        } catch (PersistenceException fatal) { throw fatal; }
        catch (IOException invalid) { return failure(timedOut.get() ? "PTC_TIMEOUT" : "PTC_TRANSPORT_ERROR: " + invalid.getClass().getSimpleName()); }
        finally {
            timer.shutdownNow();
            if (process != null) {
                process.destroyForcibly();
                try { process.getOutputStream().close(); process.getInputStream().close(); }
                catch (IOException ignored) { }
                remove(name);
            }
        }
    }

    private static PtcRunResult failure(String error) { return new PtcRunResult(null, List.of(), error); }
    private static void send(OutputStream output, JsonNode value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        if (bytes.length > MAX_FRAME_BYTES) throw new IOException("PTC frame exceeds limit");
        output.write(bytes); output.write('\n'); output.flush();
    }
    private static JsonNode read(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int b; (b = input.read()) != -1;) {
            if (b == '\n') return JSON.readTree(bytes.toByteArray());
            if (bytes.size() >= MAX_FRAME_BYTES) throw new IOException("PTC frame exceeds limit");
            bytes.write(b);
        }
        if (bytes.size() != 0) throw new IOException("Incomplete PTC frame");
        return null;
    }
    private static boolean missing(String text) { return text.contains("No such container") || text.contains("No such object"); }
    private static void remove(String name) {
        try {
            var runner = new LocalProcessRunner();
            String last = "";
            for (int attempt = 0; attempt < 4; attempt++) {
                var removed = runner.run(List.of("docker", "rm", "--force", name), Duration.ofSeconds(10));
                if (!removed.isTimedOut() && (removed.getExitCode() == 0 || missing(removed.getOutput()))) return;
                // --rm may already be removing a normally completed container. Verify absence,
                // rather than interpreting an in-progress removal as failure or ignoring daemon errors.
                var inspected = runner.run(List.of("docker", "inspect", name), Duration.ofSeconds(5));
                if (!inspected.isTimedOut() && inspected.getExitCode() != 0 && missing(inspected.getOutput())) return;
                last = removed.getOutput();
                Thread.sleep(50);
            }
            throw new IOException("Cannot confirm container removal: " + last);
        } catch (IOException failure) { throw new PersistenceException("Cannot confirm PTC container cleanup; execution stopped", failure); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new PersistenceException("PTC cleanup interrupted; execution stopped", failure);
        }
    }
}
