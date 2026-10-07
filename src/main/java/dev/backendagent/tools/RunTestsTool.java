package dev.backendagent.tools;

import java.util.Map;
import dev.backendagent.model.ToolResult;
import dev.backendagent.sandbox.DockerSandboxExecutor;

public final class RunTestsTool implements Tool {
    private final WorkspaceAccess workspace;
    private final DockerSandboxExecutor executor;
    public RunTestsTool(WorkspaceAccess workspace, DockerSandboxExecutor executor) {
        this.workspace = workspace;
        this.executor = executor;
    }
    public String name() { return "run_tests"; }
    public ToolDefinition definition() {
        return new ToolDefinition(name(), "在受限 Docker 容器中，对当前工作区的过滤源码快照运行固定的离线 mvn test。"
                + "返回退出码、超时状态和日志。仅支持根目录 pom.xml 的简单单模块 Maven 项目；依赖须在镜像内预装。"
                + "失败后根据日志调整；环境或依赖错误不能当作代码缺陷。不能指定命令、镜像或路径。", Map.of());
    }
    public ToolResult execute(Map<String, String> arguments) {
        if (!arguments.isEmpty()) { return new ToolResult(false, "run_tests 不接受参数"); }
        return executor.runTests(workspace);
    }
}
