# 程序化工具调用（PTC，add_ptc 分支实验）

PTC 让模型提交 Python 程序，由程序通过 `await tools.name({...})` 连续调用工具，循环、分页或整理中间结果，最后只将 `return` 值和 `print` 输出返回模型。它与普通同批调用的区别是：后一调用可以使用前一调用刚返回的数据，不必再次请求模型。

本实现参考 [DeepSeek Harness 的 PTC 执行器](https://github.com/deepseek-ai/deepseek-harness/tree/22ddee3792402c3e70ec5f86a63c3e3e895a2f60/packages/core/tool-executor-ptc)。当前采用增量接入：保留普通工具的全量定义，增加可选 `run_code`，而非切换成只有程序入口的执行模式。PTC 默认关闭，不与工具筛选模式同时启用。

## 构建与使用

```bash
mvn clean package
docker build -f sandbox/ptc/Dockerfile -t backend-agent-ptc-runtime:local .
java -jar target/backend-engineer-agent-2.2.0.jar \
  --interactive --workspace examples/test-repair \
  --config agent-local.properties --max-model-calls 50 \
  --tool-selection all --ptc true
```

普通文件工具和 Maven 测试仍需要原来的 `backend-agent-sandbox:local` 镜像。`--ptc-image` 可由操作者指定其他本地运行时镜像；模型不能指定镜像、主机路径或启动命令。打开已有会话时，也需显式使用 `--ptc true`。

## 首版能力

由实际注册工具的输入定义自动生成 Python SDK，首版提供：

| 绑定 | 程序返回值 |
| --- | --- |
| `search_history` | 历史索引 JSON，含 `matches`、`nextBeforeSequence` |
| `read_observation` | `sourceCallId`、正文 `content` 及来源/时效 `evidence` |
| `search_turns` | 对话索引 JSON，含消息 ID、轮次、角色 |
| `read_turn` | 去除展示行号的消息正文及原行范围、后续轮次标记 |
| `integer_calculate` | `expression`、`result_name`、十进制字符串 `value` |

例如，根据搜索结果动态提取消息：

```python
page = await tools.search_turns({
    'keyword': '告警', 'role': 'user', 'before_turn': '0', 'limit': '3'
})
rows = []
for reference in page['matches']:
    message = await tools.read_turn({
        'message_id': reference['messageId'], 'start_line': '1', 'end_line': '40'
    })
    rows.append({'messageId': message['messageId'], 'content': message['content']})
return rows
```

模型提交的是异步函数体，不包含 `def` 包装。绑定参数使用一个 dict；所有原工具的参数和会话限制仍有效。失败的工具抛出 `ToolCallError`，可通过 `error.tool_name` 定位并用 `try/except` 处理。第一版子调用串行，未引入并行写入或持久 REPL。

文件修改、文件读取和测试继续使用原工具。程序输出标明 `REFERENCE_ONLY`，不是新的当前文件或测试证据。程序不提供源码文件读取/写入、Shell 或嵌套 `run_code` 绑定；PTC 返回的历史片段不自动成为新的当前文件读取证明，精确补丁继续执行原有来源校验。

## 执行与持久化

- Java 控制工具绑定、参数校验、调用次数和会话身份。容器只运行 Python，不挂载源码、副本、会话目录、密钥或 Docker socket；网络关闭、根目录只读，临时目录限量。
- 每次启动新容器，结束或超时后清理。默认执行期限60秒，最多16次子调用；代码最多8000字符，程序结果及打印合计最多6000字符，中间工具正文合计最多512 KiB。
- 子调用写入 `PTC_TOOL_CALL_STARTED` / `PTC_TOOL_CALL_COMPLETED`，保存父调用 ID、运行 ID、独立子调用 ID，以及参数和原始结果。只有外层 `run_code` 进入普通工具历史。
- 历史查询可追溯子调用；checkpoint 恢复时只将外层调用与普通工具历史配对，不把子调用重新塞回上下文。捕获过的工具失败仍在外层 `failedCalls` 报告；完整原文保留在归档。
- 程序无持久变量；恢复已完成会话不会重跑它。进程在执行中异常退出时，仍遵循原有 checkpoint 与日志一致性检查，不自动重放未知程序进度。

## 验证命令

```bash
mvn -Dtest=PtcExecutionTest test
python3 -m unittest discover -s sandbox/ptc -p 'test_*.py'
mvn -Dptc.integration=true -Dtest=DockerPtcIntegrationTest test
```

常规测试使用固定模型响应验证机制；容器集成测试运行真实 Python、RPC、工具回调与归档，不调用模型 API。真实模型的请求数和 Token 收益须通过同任务实测，不能仅由过滤后正文变短推断。

## 已验证范围

- 6 项 Java 机制测试与 5 项实际容器集成测试，覆盖工具白名单、调用限额、父子归档、完成会话恢复、错误反馈、只读容器、输出上限和超时清理。
- 5 项 Python 协议测试，覆盖依赖调用、异常捕获、语法错误及输出上限。
- 真实模型小测：一次 `run_code` 完成搜索与依赖提取，返回正确的和与最大值；2 次 API 请求、9,438 Token。该样例验证程序编排功能，不作为复杂任务的性能缩减率。
- PTC 会话含有新子调用事件，使用本分支读取；已发布 v2.2.0 和 `main` 不包含此实验。完整执行记录仅保存于本地 `reports/`。
