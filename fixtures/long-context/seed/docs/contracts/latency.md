# LATENCY-v2 最近秩百分位
LatencyStats.percentile(List<LogEntry> entries,int percent)返回long耗时，使用nearest-rank。
percent允许1..100，其他值抛IllegalArgumentException。null列表或null元素也抛IllegalArgumentException。
先按耗时升序排列副本，秩rank=ceil(percent*n/100)，取下标rank-1。使用整数long运算，不做线性插值，不改变输入顺序。
空列表且percent合法返回0。单条记录任意百分位返回该耗时。
例：[1,2,3,4]的P50是2，P95是4；[1..20]的P95是19；21条记录P95取第20条。
所有状态的有效记录都参与百分位，不能只统计成功请求。
