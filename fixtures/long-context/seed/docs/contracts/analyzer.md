# ANALYZE-v2 汇总入口
LogAnalyzer.analyze(List<String> lines)返回long[8]：[有效数,非法数,2xx数,4xx数,5xx数,总耗时,P95,告警标记0或1]。
null列表抛IllegalArgumentException。null行或空白行跳过，不计有效或非法。
每条非空行调用LogParser.parse；解析失败的IllegalArgumentException计入非法数，其他行继续处理。
复用StatusMetrics、LatencyStats与AlertEvaluator，不重复实现规则。默认告警minSamples=3、thresholdBasisPoints=2000。
数组长度和字段顺序固定；所有非法行时返回[0,非法数,0,0,0,0,0,0]。非法行不能进入告警分母或耗时。
例如3条有效记录(200,10ms)、(404,20ms)、(500,30ms)，再加一条非法日志和一条空白，初始返回[3,1,1,1,1,60,30,1]。
修改告警错误定义后只能改变告警字段，状态计数、耗时和P95必须保留。
