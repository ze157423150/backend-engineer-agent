# 运行演示：修复、追加需求与恢复

以下命令从仓库根目录执行，需要 JDK 21、Maven 3.8.7+、本机 Linux Docker。模型调用会使用 API 额度；工具执行顺序与调用次数由模型决定，示例输出是验收目标，不保证每次完全一致。

## 更新代码与Docker权限

Java源码修改后，需要重新构建JAR；仅重启旧JAR不会加载修改后的源码：

```bash
mvn clean package
docker ps
```

如果docker ps报permission denied，且 `getent group docker` 已列出当前用户，刷新当前登录会话：

```bash
newgrp docker
docker ps
```

也可以重新登录后再启动。仅更新终端输入层或宿主机错误提示时无需重建沙箱镜像；修改容器worker或协议时需要重建。权限问题本身不会通过重新编译解决。

## 1. 准备构建与配置

```bash
mvn clean verify
docker build -f sandbox/Dockerfile -t backend-agent-sandbox:local .
```

首次配置时复制模板；已有配置时跳过复制，避免覆盖。

```bash
cp agent-local.properties.example agent-local.properties
```

在编辑器中填写 `model.api-key`。模型名称使用账号可用值；服务地址填根地址或 `/v1`，不要填完整 `/chat/completions`。共享预算默认输出2048 tokens；较大补丁出现 LENGTH 时，可以在本地配置中调为 `model.max-output-tokens=4096` 后恢复，完整输入预算检查仍会生效。

```bash
java -jar target/backend-engineer-agent-0.1.0-SNAPSHOT.jar --help
```

## 终端连续对话（推荐日常使用）

```bash
java -jar target/backend-engineer-agent-0.1.0-SNAPSHOT.jar \
  --interactive --workspace examples/test-repair \
  --config agent-local.properties --data-dir .agent-sessions \
  --max-model-calls 60
```

看到 `你>` 后输入一行需求，例如：

```text
你> 先运行测试定位失败，修复Calculator.add，不修改已有测试预期，再测试。
Agent> ……修复结果……
你> 继续补充负数和零值测试，保留已有测试，然后运行测试。
Agent> ……测试结果……
你> /status
你> /exit
```

每轮复用同一会话、工作副本和上下文管理机制。只打印本轮回答与简短状态；完整日志仍写events.jsonl。尚未选会话时，普通输入创建新会话；已在会话中则追加当前会话轮次，只有 `/new` 后输入才新建。目前输入以一行一条消息提交，允许以绝对文件路径开头；模型调用文件工具使用工作区相对路径，例如 `src/main/java/example/Calculator.java`。回答在本轮处理结束后显示，压缩与重试进度实时打印。

| 命令 | 作用 |
| --- | --- |
| /help | 查看命令 |
| /delete UUID | 展示任务及未写回改动，准确确认后永久删除非当前且未被占用的会话 |
| /sessions | 列出当前数据目录的会话ID、状态、保存时间和首条任务摘要 |
| /open UUID | 打开已有会话；只加载与核验，不自动请求模型 |
| /new | 输入新需求后创建独立会话，重新复制原项目 |
| /diff | 查看所有待写回改动及冲突，不修改文件、不调用模型 |
| /apply | 完成轮次后将改动写回原项目，哈希冲突时整批取消；不调用模型 |
| /status | 查看会话ID、轮次、累计调用、工作区版本和测试状态 |
| /budget N | 设置整个会话累计上限，必须大于已用次数；不自动执行任务 |
| /resume | 继续失败或预算停止的当前轮；不会新增用户消息 |
| /exit、/quit | 在输入边界退出，保存的记录保留 |

调用额度不足时先 `/budget 100`，再重新输入需求；当前轮失败/预算停止时，新需求不被添加，先处理原因并 `/resume`。未知in-flight工具不会在终端自动重放，需要退出后通过现有失败恢复命令显式核验。配置只在启动时读取，LENGTH需要调整本地输出预算并重启恢复。

重新接入COMPLETED会话：

```bash
java -jar target/backend-engineer-agent-0.1.0-SNAPSHOT.jar \
  --interactive --workspace examples/test-repair \
  --continue-session "实际的Session-ID" \
  --config agent-local.properties --data-dir .agent-sessions \
  --max-model-calls 100
```

只接入不会请求模型，输入下一条需求才新增一轮。预算/失败会话可在相应恢复命令中添加 `--interactive`，先恢复当前轮，再继续终端输入。会话锁从打开保持到退出，不允许另一个进程同时修改同一会话。

首条消息前 `/exit` 或输入EOF不创建空会话；各轮输入EOF正常退出。交互程序退出码0表示正常退出终端，不代表所有轮次都成功，需看状态。处理中直接中断进程仍受既有checkpoint恢复边界限制。

以下保留每轮独立启动的命令，便于脚本调用。

## 2. 首轮：让 Agent 修复失败测试

[Calculator.java](../examples/test-repair/src/main/java/example/Calculator.java) 故意把 add 写成减法，[CalculatorTest.java](../examples/test-repair/src/test/java/example/CalculatorTest.java) 要求 `add(2, 3) == 5`。

```bash
java -jar target/backend-engineer-agent-0.1.0-SNAPSHOT.jar \
  --workspace examples/test-repair \
  --task "先调用run_tests定位CalculatorTest失败；修复Calculator.add的实现，不要修改现有测试或预期值。再调用run_tests验证，并用workspace_diff检查改动，最终解释修复和测试结果。" \
  --config agent-local.properties \
  --data-dir .agent-sessions \
  --max-model-calls 16
```

观察：先有失败测试结果，模型读取相关文件、调用补丁工具，把减法改为加法，再运行测试确认通过。修改出现在控制台打印的 `Isolated workspace` 中，仓库里的演示源码仍保留原错误，便于再次复现。

程序最后输出 `Status`、`Model calls`、`User turn`、工作区版本、测试状态和最终回答。`Status: COMPLETED` 表示模型结束本轮；测试验收要同时看 `Test status: CURRENT_PASSED` 和实际结果。

## 3. 第二轮：在同一个会话补充测试

复制启动时打印的 UUID 到下面变量。这里复制的是会话 ID，不需要在终端粘贴 API key。

```bash
SESSION_ID='替换为会话UUID'
java -jar target/backend-engineer-agent-0.1.0-SNAPSHOT.jar \
  --workspace examples/test-repair \
  --continue-session "$SESSION_ID" \
  --message "继续在同一会话中，为Calculator.add补充负数和零值测试，保留现有测试不变；运行run_tests并说明实际测试结果。" \
  --config agent-local.properties \
  --data-dir .agent-sessions \
  --max-model-calls 30
```

第二轮继承第一轮工作副本和历史，`User turn` 增至2；调用上限30是整个会话累计额度。运行前要求第一轮已经COMPLETED，不能用追加需求入口替代失败或预算恢复。

短演示通常不会达到摘要触发门槛。长历史、跨轮摘要和过期证据的效果看[验证报告](VALIDATION.md)，不要把这个两轮演示当成摘要压缩效果证明。

## 4. 查询和检查产物

```bash
java -jar target/backend-engineer-agent-0.1.0-SNAPSHOT.jar \
  --show-session "$SESSION_ID" --data-dir .agent-sessions
```

```text
.agent-sessions/<session-id>/
  session.json      会话快照、轮次与测试状态
  checkpoint.json   恢复所需的状态和工作区哈希
  events.jsonl      按顺序追加的执行日志与原始工具结果
  workspace/       修改后的隔离源码
  source-workspace.txt  原项目路径
  source-sync.json  /apply 后保存的同步基准（可选）
  session.lock     并发打开与删除保护
```

查询不调用模型。不手工修改日志或 checkpoint，恢复会联合核验这些记录。`MODEL_CALL_STARTED` 是任务请求；摘要请求由相应COMPACTION事件标识，总modelCalls包含二者。

## 5. 预算停止与失败恢复

预算耗尽时继续原轮次，不追加用户消息：

```bash
java -jar target/backend-engineer-agent-0.1.0-SNAPSHOT.jar \
  --workspace examples/test-repair --resume-session "$SESSION_ID" \
  --config agent-local.properties --data-dir .agent-sessions \
  --max-model-calls 50
```

FAILED时使用独立入口：

```bash
java -jar target/backend-engineer-agent-0.1.0-SNAPSHOT.jar \
  --workspace examples/test-repair --resume-failed-session "$SESSION_ID" \
  --config agent-local.properties --data-dir .agent-sessions \
  --max-model-calls 50
```

每次恢复上限必须大于累计已用次数，并沿用相同来源与数据目录。故障可能再次发生；先看MODEL_CALL_FAILED诊断。例如OUTPUT_LIMIT应调整输出预算，不依赖重复同样请求解决。

工具正在执行、结果尚未确认时，需要显式 `--retry-incomplete-tool true`，并且前后文件哈希一致。该参数只处理当前未确认工具，不整批重跑；文件已改变或日志无法核验时程序会拒绝。正常COMPLETED会话不需要恢复。

## 6. 导出到新目录

```bash
java -jar target/backend-engineer-agent-0.1.0-SNAPSHOT.jar \
  --export-session "$SESSION_ID" \
  --output-workspace /tmp/backend-agent-review-copy \
  --data-dir .agent-sessions
```

目标目录必须尚不存在。导出不调用模型或Docker，不覆盖原项目；检查导出代码后自行决定如何合并。导出的是过滤后的源码，不含Git历史与会话记录。

## 7. 将副本改动写回原项目

Agent 修改的是 `.agent-sessions/<UUID>/workspace`。本轮完成后在终端输入：

```text
/diff
/apply
```

`/diff` 比较原项目当前内容与副本内容；`/apply` 只同步通过文件工具修改或创建的文件，不复制整仓库。首次同步以首次修改前的原文哈希为基准；以后以上次成功同步的哈希为基准。已有文件与基准不符、同名新文件已被其他程序创建且内容不同，会整批取消。来源和副本完全相同时不重复写入。新增空文件也会同步。

写回信息保存在会话目录的 `source-sync.json`，重新打开已完成会话后仍可继续使用：

```bash
java -jar target/backend-engineer-agent-0.1.0-SNAPSHOT.jar \
  --interactive --continue-session "$SESSION_ID" \
  --workspace examples/test-repair --config agent-local.properties \
  --max-model-calls 60
```

旧进程需要 `/exit` 后用更新后的 JAR 重启；其他机器同步源码后执行 `mvn clean package`。这些终端同步层的更新不需要重建 Docker 镜像；修改 worker 或协议时仍需重建。

写回现有文件采用单文件原子替换并保留权限，新增文件采用 CREATE_NEW。所有文件先做冲突检查，每个文件写入前再次检查；多文件写回并非整体事务，如果写入期间发生 I/O 故障，终端报告已写回文件，不自动回滚。失败或额度耗尽的未完成轮次只能 `/diff`，完成后才能 `/apply`。不支持自动删除原项目文件，原项目外部修改也不会自动导入当前副本。

## 8. 选择与切换会话

在刚启动的终端或已有对话中均可使用：

```text
/sessions
/open 完整会话UUID
/new
```

`/new` 后输入第一条需求才创建新目录；新会话从原项目重新复制，旧会话未写回的副本改动不会进入新会话。`/open` 只打开，不自动继续模型执行；已完成会话可直接追加需求，失败或额度停止的会话需要 `/budget N` 和 `/resume`。存在结果不确定的 in-flight 工具时，普通 `/open` 不重放，仍需现有显式失败恢复命令。

每次打开使用新的会话存储、工作区、工具集合、历史检索和同步器，打开成功后释放旧会话锁；打开失败仍保留原会话。工具历史、两类摘要、用户轮次、检查点、工作记忆、调用计数及 source-sync.json 均按UUID分开。当前进程中 `/budget N` 的设置也按会话独立保留；新进程仍使用启动参数设置上限。列表时间为最近保存快照时间。

列表包括同一 `--data-dir` 下的历史会话。只能打开与当前 `--workspace` 来源一致的会话；打开其他项目会拒绝，原会话不受影响。切换不会执行 `/apply`，也不删除原会话。两个会话分别修改同一原文件时，写回仍检查各自基准，不会因切换绕过冲突保护。显式 `--memory-from` 仅用于本次启动最初创建的会话，后续 `/new` 不自动继承。

## 9. 删除会话

启动选择界面或已有对话中均可输入：

```text
/delete 完整UUID
```

终端展示会话任务、状态和未写回文件，再要求输入 `DELETE 完整UUID`。必须与提示中的确认文本完全一致；中文“确认”、`y`、`yes` 或其他输入以及EOF都会取消，不会发送给模型。当前会话不能删除；先打开另一会话，或退出后重启至会话选择界面。被其他进程占用的会话也不能删除。

删除会永久清除该会话目录中的历史、摘要、checkpoint、隔离副本及 source-sync.json，不撤销已写回原项目的代码。预览以 checkpoint 与当前副本哈希核验跟踪文件，并和原项目比较；元数据损坏、未完成工具批次或现场不符时，提示未写回改动无法核验，不当作无改动。

预览到确认期间持有同一会话锁。确认后先将目标目录原子移动为数据目录下隐藏的 `.deleted-...` 清理目录，再清除；不跟随符号链接。若清理期间I/O失败，会报告残留位置，不误报全部删除成功。删除无需调用模型或启动Docker。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| Docker连接或权限失败 | 确認本机Docker daemon和当前用户权限；项目不会回退到宿主机执行 |
| 镜像不存在或worker不匹配 | 使用当前源码重新构建backend-agent-sandbox:local |
| 离线Maven缺依赖 | 为目标项目提前准备镜像依赖；修改代码本身无法解决未预装依赖 |
| 返回LENGTH | 调整model.max-output-tokens或缩小任务，再恢复；输出预算增大可能降低可用输入空间 |
| COMPLETED但测试没通过 | 模型结束不等于验收通过；查看当前测试状态和执行结果 |
| CREATED会话无法打开 | 可能是初始化失败残留；/open不把CREATED当作可续聊会话，可/delete确认清理 |
| 删除时提示未写回改动无法核验 | 缺少完整checkpoint、元数据损坏或副本不一致；不代表一定有未写回改动 |
| 输入“确认”没有删除 | 必须输入提示中的DELETE与完整UUID，严格匹配 |
| 根目录test.java未被测试 | Maven默认只编译src/main/java及src/test/java，测试通过不证明根目录文件已验证 |
| 日志没有逐条实时出现 | 压缩进度实时打印，完整事件在任务结束后统一打印 |

[返回项目首页](../README.md) · [设计说明](ARCHITECTURE.md)
