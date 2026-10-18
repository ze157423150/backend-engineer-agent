# ALERT-v1 错误率告警
AlertEvaluator.evaluate(List<LogEntry> entries,int minSamples,int thresholdBasisPoints)返回boolean。
初始规则：错误分子仅500..599的5xx请求。分母是所有有效记录，包括1xx/2xx/3xx/4xx。
有效记录数达到minSamples且错误率>=thresholdBasisPoints/10000时告警。边界是大于等于，不是严格大于。
minSamples>=1，thresholdBasisPoints在0..10000；null列表或null元素均抛IllegalArgumentException。
空列表或不足最小样本不告警。达到最小样本且阈值0时，即使0错误也告警，这是明确规定。
计算用long整数交叉乘，避免整数除法提前截断和浮点误差。空样本不能除0。
例：1条500+4条200，minSamples=5,threshold=2000恰好告警；4条500但minSamples=5不告警。
429的状态计数归4xx，本版本不计入告警错误分子。
