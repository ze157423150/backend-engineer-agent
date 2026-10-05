package dev.backendagent.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import dev.backendagent.model.ToolResult;
import dev.backendagent.tools.Workspace;

/** Fixed Maven test command in an offline, limited container using a filtered source snapshot. */
public final class DockerSandboxExecutor {
    public static final String DEFAULT_IMAGE = "backend-agent-sandbox:local";
    private final CommandRunner runner;
    private final String image;
    private final Duration timeout;

    public DockerSandboxExecutor(CommandRunner runner, String image, Duration timeout) {
        if (image == null || !image.matches("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,200}")) {
            throw new IllegalArgumentException("Invalid sandbox image name");
        }
        if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Test timeout must be positive and at most 600 seconds");
        }
        this.runner = java.util.Objects.requireNonNull(runner);
        this.image = image;
        this.timeout = timeout;
    }

    public ToolResult runTests(Workspace workspace) {
        Path snapshot = null;
        String name = "backend-agent-test-" + UUID.randomUUID();
        ToolResult result;
        boolean attempted = false;
        boolean interrupted = false;
        try {
            snapshot = workspace.createTestSnapshot();
            attempted = true;
            CommandResult execution = runner.run(command(snapshot, name), timeout);
            boolean passed = !execution.isTimedOut() && execution.getExitCode() == 0;
            result = new ToolResult(passed, "status=" + (execution.isTimedOut() ? "TIMED_OUT" : passed ? "PASSED" : "FAILED")
                    + "\nexitCode=" + execution.getExitCode() + "\ncommand=mvn -o -B -ntp test"
                    + "\n测试对象为本轮源码快照；退出码 0 表示 Maven test 阶段成功，不保证存在测试或覆盖全部需求。\n"
                    + execution.getOutput());
        } catch (IOException failure) {
            result = new ToolResult(false, "status=ERROR\n无法准备源码快照或启动 Docker；请检查 Docker 安装、服务和权限、"
                    + "本地沙箱镜像及根目录 pom.xml。未在宿主机运行 Maven。\n");
        } catch (InterruptedException failure) {
            interrupted = true;
            result = new ToolResult(false, "status=INTERRUPTED\n测试请求被中断。\n");
        } finally {
            // Killing the docker CLI alone does not guarantee that the container stops.
            if (attempted) {
                try {
                    CommandResult cleanup = runner.run(List.of("docker", "rm", "-f", name), Duration.ofSeconds(10));
                    if (cleanup.isTimedOut() || (cleanup.getExitCode() != 0
                            && !cleanup.getOutput().contains("No such container"))) {
                        System.err.println("Sandbox cleanup needs checking: " + name);
                    }
                } catch (IOException | InterruptedException failure) {
                    System.err.println("Sandbox cleanup could not finish: " + name);
                    if (failure instanceof InterruptedException) { interrupted = true; }
                }
            }
            if (snapshot != null) {
                try (var paths = Files.walk(snapshot)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
                } catch (IOException failure) { System.err.println("Sandbox snapshot cleanup failed."); }
            }
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
        return result;
    }

    private List<String> command(Path snapshot, String name) {
        return List.of("docker", "run", "--rm", "--pull=never", "--name", name,
                "--network", "none", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--pids-limit", "128",
                "--memory", "768m", "--memory-swap", "768m", "--cpus", "1", "--user", "10001:10001",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=128m",
                "--tmpfs", "/workspace/target:rw,nosuid,nodev,size=256m,uid=10001,gid=10001",
                "--mount", "type=bind,src=" + snapshot + ",dst=/workspace,readonly",
                "--workdir", "/workspace", "--env", "HOME=/tmp", "--env", "MAVEN_OPTS=-Xmx256m -Duser.home=/tmp",
                "--entrypoint", "mvn", image, "-o", "-B", "-ntp", "-Dmaven.repo.local=/opt/maven-repository", "test");
    }
}
