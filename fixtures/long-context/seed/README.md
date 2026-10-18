# Access log analyzer
这是提取自网关离线回放流程的Java核心模块，不依赖Spring或数据库。
公共接口位于src/main/java/telemetry，必须保留方法签名与返回数组布局。
权威规范是docs/contracts，事故与data中的历史结果仅供定位，不是期望值。
实现、测试和文档必须遵循最新需求；逐轮处理，不提前修改未授权的其他模块。
使用JDK21与Maven，禁止改pom或加依赖。config/release.properties是发布核对清单。
日志格式timestampMillis|service|status|latencyMillis，历史数据可能有非法行。
