# 程序化工具调用

本项目参考 [DeepSeek Harness 的 PTC 执行器](https://github.com/deepseek-ai/deepseek-harness/tree/22ddee3792402c3e70ec5f86a63c3e3e895a2f60/packages/core/tool-executor-ptc)，默认采用 PTC：模型只能直接调用 `run_code`，现有工具根据实际注册状态生成 Python SDK，在程序内通过 `await tools.name(参数名=值)` 调用。不存在运行失败后自动切回原生工具的降级。

程序负责循环、条件判断和数据整理；需要业务推理时，将必要的原文和错误返回模型。后一工具可使用前一工具返回的数据，无须再请求一次模型。PTC 的 SDK、程序和最终输出仍占上下文，不能据此直接推断 Token 收益。

## 构建与启动

```bash
mvn clean package
docker build -f sandbox/Dockerfile -t backend-agent-sandbox:local .
docker build -f sandbox/ptc/Dockerfile -t backend-agent-ptc-runtime:local .
java -jar target/backend-engineer-agent-2.3.0.jar \
  --interactive --workspace examples/test-repair \
  --config agent-local.properties --max-model-calls 50
```

`--ptc true` 已是默认值，工具集合使用默认 `--tool-selection all`。工具筛选模式不能与 PTC 同时启用。`--ptc false` 仅保留作原生模式回归、对照或显式回退。运行时镜像由操作者通过 `--ptc-image` 指定，模型不能指定启动命令、镜像或宿主机挂载。

## 工具与程序值

SDK 覆盖当前注册的文件搜索、读取、创建、补丁、测试、计算、历史检索、记忆及改动查看工具；未注册的工具不能使用。没有开放任意 Shell 或嵌套 `run_code`。

- `read_file` 返回 `content`（去掉展示行号和 file 头的源码）、`fileFingerprint`、`readMetadata` 和 `call_id`。必须明确提供 `path`、`start_line` 和 `end_line`，每次最多 200 行；缺少行号不会自动读取首页。`reachedEndOfFile` 表明是否读到文件末尾，不重复提供两份源码。
- `run_code` 的 `code`、`description` 均必填。`description` 保存本次程序的具体用途；缺少时拒绝执行并返回明确反馈，不自动补通用说明。
- 搜索历史与对话返回结构化索引；`read_turn` 返回无展示行号的消息正文及范围；`read_observation` 保留历史来源与时效性。
- 计算返回十进制字符串 `value`。其他工具返回 `successful`、原始文本 `content`、可用的指纹/范围，以及 `call_id`。
- SDK从实际工具定义生成必填关键字签名，推荐 `await tools.read_file(path='A.java', start_line=1, end_line=40)`；兼容单个 dict，但不能与关键字混用。Python绑定和Java执行层均校验参数，不补行号。字符串和整数按原协议规范化，`apply_patch_batch.patches` 仍为 JSON 字符串。
- 失败抛出 `ToolCallError`，可读取 `tool_name`、`message` 并捕获处理。绑定阶段缺参会在RPC前拒绝，不执行工具；已提交的失败子调用计入 `failedCalls`。

外层严格调用通过 `model.ptc-strict-tool-calls=true` 显式开启，仅约束 `run_code`，不改变原生工具协议。DeepSeek官方严格模式需将 `model.base-url` 设为 `https://api.deepseek.com/beta`；其它服务须先验证兼容性，程序不自动改写端点或静默降级。严格模式约束外层 `code`/`description`，不能约束Python字符串中的内部调用；Java仍校验非空和长度。默认配置保持关闭以兼容其它服务。

例如，程序中直接修复已经读取到的片段，并运行测试：

```python
source = await tools.read_file({
    'path': 'src/main/java/example/Calculator.java',
    'start_line': 1, 'end_line': 40
})
if 'return left - right;' in source['content']:
    await tools.apply_patch({
        'path': 'src/main/java/example/Calculator.java',
        'old_text': 'return left - right;',
        'new_text': 'return left + right;'
    })
test = await tools.run_tests({})
return {'test': test['content']}
```

提交的是异步函数体，无须写 `def`。当前子调用串行；同文件多处修改使用 `apply_patch_batch`，不会将程序内任意修改自动合并为原子事务。

## 隔离、状态与恢复

Java 在宿主机控制工具身份、参数与执行。Python 容器不挂载项目、会话目录、密钥或 Docker socket，无网络、非 root、根目录只读；文件操作和 Maven 测试仍由原有工作区与测试容器完成。

每次 `run_code` 启动新容器，默认期限 180 秒（命令行按 Maven 测试期限加 60 秒设置，整个程序共享此期限），最多 16 次子调用；代码最多 32,000 字符、单个字符串参数最多 24,000 字符，原工具限制仍生效。程序返回与打印输出合计最多 24,000 字符（与原有单工具输出上限对齐，实际请求仍受上下文预算约束），工具结果正文累计最多 512 KiB。超时、输出超限和清理失败均不会被当作成功。

子调用复用原生工具执行及结果记账：读取建立指纹证据，修改更新工作区版本并使旧证据失效，测试更新当前版本验证状态。脚本返回“通过”不能代替真实测试；捕获测试失败也不能绕过结束检查。

子调用带 `parentCallId`，完整参数和结果进入执行账本及事件归档，供 checkpoint 校验、状态重建和历史提取。发送模型请求时排除这些独立子调用消息，仅展示外层 `run_code` 及其限长结果；不会把中间正文再次完整堆入对话。

已完成边界可恢复，恢复不重放已完成工具。没有实现 Python 程序内部断点续跑：外层程序仍在执行且已有子调用时，禁止直接整段重试，即使工作区哈希未变化。程序错误不会回滚已完成修改，后续应检查实际状态后发出新的程序。

## 验证

```bash
mvn -Dtest=PtcExecutionTest,PtcPipelineTest,DeepSeekModelClientTest test
python3 -m unittest discover -s sandbox/ptc -p 'test_*.py'
mvn -Ddocker.integration=true -Dptc.integration=true verify
```

机制测试覆盖统一入口、参数边界、执行账本、证据失效、测试失败拦截和恢复；Docker 集成包含 Python → Java → 隔离工作区文件修改 → Maven 测试 → 已完成会话恢复的完整链路。真实模型记录和命令仅保存在本地 `reports/`；本分支不改变 `main` 和已发布版本。

## 请求预算压力下的兜底

窗口上限与保守 Token 估算方式保持原设置。预算压力下，符合历史轮次范围但曾因太短而跳过的对话可以参与一次摘要，压缩目标按输入长度收紧，仍需通过来源与收益检查；已实际尝试的同一批次不会反复调用摘要。

若继续收缩工具历史仍不足，新的投影规则可以节选 `run_code` 的展示正文，动态分配有限的输出空间。`outputTruncated=true`、`archiveCallId` 和省略说明会明确提示信息不完整，原结果及子调用仍保存在归档。失败回执、来源和错误等安全信息保留，不能为了节省空间伪造成功或静默丢弃这些信息。旧投影按旧版本验证。

历史证据只有在程序输出中包含来源、版本、范围、正文完全一致的证据对象，且时效状态没有变化时才去重。参数不全会列出 `missing`、`unexpected` 与查询默认值的填写方式，校验失败不会执行工具。

## 参数与程序错误反馈

`run_code` 的 JSON、工具名和参数类型仍由模型协议层检查；缺失、多余、空白或超长的字段由程序入口返回 `missing`、`unexpected`、`invalid`，不启动容器。失败记录进入下一次请求，模型可以据此修正；不会自动补写缺失参数或执行未通过校验的程序。

Python 语法或运行错误保留异常类型、消息和原程序行号，例如 `TypeError at program line 12: ...`，不附宿主机及运行时完整堆栈。程序已发生的工具操作仍按原规则保存，不因出现行号而自动回滚或整段重放。
