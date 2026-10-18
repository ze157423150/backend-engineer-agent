# PARSE-v2 访问日志解析
LogParser.parse(String line)返回LogEntry。LogEntry是不可变数据类，公开final字段timestampMillis(long)、service(String)、status(int)、latencyMillis(long)，构造器参数按上述顺序。
恰好4个竖线分隔字段，必须保留末尾空字段以判定非法输入，每个字段trim。
时间戳是ASCII十进制数字串，范围0..Long.MAX_VALUE；status是100..599；耗时是0..3600000毫秒。
数字字段禁止空白、正负号、小数及溢出；字段trim后可有前导零。
service先trim后用Locale.ROOT转小写，必须匹配[a-z][a-z0-9-]{0,31}，不能默默默认服务名。
null、空白、字段数量不符、格式/范围不符均抛IllegalArgumentException，不泄露其他异常类型。
例：" 100 | API-GW | 200 | 15 "变成(100,api-gw,200,15)。"1|api|200|"应拒绝，不能误判成缺少可选字段。
