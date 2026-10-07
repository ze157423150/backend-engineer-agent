package dev.backendagent.model;

import dev.backendagent.sandbox.*;
import dev.backendagent.tools.Workspace;
import java.nio.file.*;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Runs the exact disputed example against old and agent-modified code in Docker. */
public final class ArithmeticDiagnosisEvaluation {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var output = Path.of(args[0]);
        var executor = new DockerSandboxExecutor(new LocalProcessRunner(), DockerSandboxExecutor.DEFAULT_IMAGE, Duration.ofSeconds(90));
        var report = json.createObjectNode();
        var runs = report.putArray("runs");
        for (String label : new String[] {"old", "current"}) {
            var result = executor.runTests(new Workspace(output.resolve(label), Path.of("agent-local.properties"), output.resolve("sessions")));
            Files.writeString(output.resolve(label + "-test.txt"), result.content());
            runs.addObject().put("version", label).put("successful", result.successful()).put("output", result.content());
            if (!result.successful() || !result.content().contains("DIAGNOSIS quantity=100 unitPrice=1.005 result="))
                throw new IllegalStateException("Docker arithmetic verification failed");
        }
        Files.writeString(output.resolve("report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Both arithmetic cases passed in Docker");
    }
}
