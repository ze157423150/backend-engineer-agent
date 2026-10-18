# METRICS-v2 状态计数
StatusMetrics.summarize(List<LogEntry>)返回固定顺序long[5]：[有效请求总数,2xx数,4xx数,5xx数,总耗时毫秒]。
2xx=200..299，4xx=400..499，5xx=500..599。1xx和3xx仅计入总数与总耗时。
空列表返回全0；null列表或null元素抛IllegalArgumentException。不改变传入列表。
累计耗时使用long，不能转int，所有请求（包含非2xx）都贡献耗时。
模块处理解析后的有效记录，不在这里统计非法日志行。429仍是4xx；告警策略改变不能修改这个计数定义。
