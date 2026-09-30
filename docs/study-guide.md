# 代码阅读与面试准备

按阶段更新。每个阶段列出必读文件、要能讲清楚的点、以及可以主动承认的不足和改进方向。

---

## 阶段 1：数据链路

按数据流动的顺序读：交易 → Flink 规则 → 告警 → 平台入库。

### 必读

#### 1. [RuleEngine.java](../stream-job/src/main/java/com/finsentinel/stream/rules/RuleEngine.java)：最重要的文件

三条规则都在这里，纯 Java，不依赖 Flink，所以能直接写单元测试。

- **velocity（交易太频繁）**：`insertSorted` 让时间保持有序，晚到的交易也能被正确计数；`pruneBefore` 清掉窗口外的记录。每个账户在一个窗口内最多报一次，避免同一波交易刷屏。
- **large amount（金额异常）**：用 Welford 算法在线计算均值和方差，只存三个数：笔数、均值、M2。账户历史不满 10 笔时不判断离群，但超过硬上限的金额无论如何都会告警。
- **被标记的金额不进基线**：`recordAmount` 只在没触发告警时调用。否则一笔欺诈会把"正常水平"拉高，下一笔欺诈就显得正常了。
- **geo jump（两国刷卡间隔太短）**：只看 `CARD_PRESENT`，因为网上支付的地点没有意义。

可能被问：
- "为什么把规则逻辑和 Flink 分开？"：可测试性。规则可以用 JUnit 毫秒级测完，Flink 函数只负责读写状态。
- "乱序数据怎么处理？"：窗口数组按事件时间有序插入，不依赖到达顺序。

#### 2. [AccountRiskState.java](../stream-job/src/main/java/com/finsentinel/stream/rules/AccountRiskState.java)

- 写成 Flink POJO 的形式：public 字段、无参构造器，字段只用基本类型、字符串和数组。
- 为什么用 `long[]` / `String[]` 而不用 `List`：Flink 遇到它不认识的类型会退回到 Kryo 序列化，Kryo 更慢，而且状态结构很难升级。
- [SerializationTest.java](../stream-job/src/test/java/com/finsentinel/stream/SerializationTest.java) 专门防止这种退化：只要有人往状态或算子间传递的类型里加了 Flink 不认识的字段，测试就会失败。

#### 3. [RiskRuleFunction.java](../stream-job/src/main/java/com/finsentinel/stream/RiskRuleFunction.java) 和 [RiskDetectionJob.java](../stream-job/src/main/java/com/finsentinel/stream/RiskDetectionJob.java)

- `keyBy(tenant|account)`：同一个账户的交易都进同一个并行实例，它的状态只存在那里，不需要加锁。
- `ValueState` 加 30 天 TTL：账户太久没有交易就清掉它的状态，控制状态总量。
- checkpoint 每 10 秒一次：给状态拍快照，同时提交 Kafka 的消费位置。作业故障恢复时，状态和消费位置一起回到这个快照，两者保持一致。
- 解析失败的消息经 side output（旁路输出）写进 DLQ，见 [ParseTransactions.java](../stream-job/src/main/java/com/finsentinel/stream/ParseTransactions.java)。一个上游发来的坏数据不会拖垮整个作业。

#### 4. [Alert.java](../common/src/main/java/com/finsentinel/common/Alert.java) 的 `idFor`，配合 [ADR 0003](adr/0003-at-least-once-alerts.md)

这是最能体现分布式功底的点，要能完整讲出来：

1. Flink sink 用 at-least-once 投递，作业从 checkpoint 恢复时，可能把一部分告警重发一遍。
2. 告警 ID 用 `tenant | account | rule | 触发交易ID` 算出一个基于名字的 UUID，同样的触发永远得到同样的 ID。
3. 平台写库时用 `on conflict (alert_id) do nothing`，重发的告警直接忽略，不会生成第二个案件。
4. 为什么不用 exactly-once：它需要 Kafka 事务，下游消费者要设成 `read_committed`，而且告警要等 checkpoint 完成才对下游可见，延迟会被拖到 checkpoint 间隔。幂等消费方案更简单，延迟也更低。

#### 5. [AlertRepository.java](../agent-platform/src/main/java/com/finsentinel/platform/alert/AlertRepository.java) 和 [KafkaErrorHandling.java](../agent-platform/src/main/java/com/finsentinel/platform/alert/KafkaErrorHandling.java)

- `insertIfAbsent` 就是上面说的幂等写入。
- 每个查询都带 `tenant_id`，这是多租户隔离的第一道防线。
- `DefaultErrorHandler`：出错先重试两次，还不行就转进 DLT，一条有毒消息不会卡死整个分区。
- `JacksonException` 不重试：格式错误的消息重试多少次也不会成功，直接进 DLT。

#### 6. [AlertIntakeIntegrationTest.java](../agent-platform/src/test/java/com/finsentinel/platform/alert/AlertIntakeIntegrationTest.java)

用 Testcontainers 起真实的 Kafka 和 Postgres，镜像版本和 docker-compose 里的一致。覆盖三种情况：

- 同一条告警投递两次，库里只有一条。
- 先来一条格式错误的消息，后面正常的告警照样能入库。
- A 租户查不到 B 租户的告警。

被问"分布式系统你怎么测"时，拿这个例子讲。

### 选读

- [ADR 0001](adr/0001-modular-monolith.md)，配合根目录的 [pom.xml](../pom.xml)：
  - 为什么根 POM 不继承 `spring-boot-starter-parent`：避免 Spring Boot 的依赖管理改掉 Flink 和 Spark 需要的版本。
  - Jackson 2 和 3 为什么能共存：groupId 和包名都不同。
- [docker-compose.yml](../deploy/docker-compose.yml)：
  - Kafka 为什么分内部和外部两个 listener：容器之间用 `kafka:19092`，本机程序用 `localhost:9092`。
  - 为什么关掉自动建主题：分区数应该是提前想清楚的决定。
  - 为什么按 profile 分组启动：控制内存占用。
- [TrafficGenerator.java](../txn-simulator/src/main/java/com/finsentinel/simulator/TrafficGenerator.java)：扫一眼就行，知道各种欺诈场景是怎么构造出来的。

### 可以主动讲的不足和改进方向

面试官问"还有什么可以改进"时，下面这几条每条都有具体方案。主动讲出来，比等面试官指出来好得多。

| 不足 | 改进方案 |
|---|---|
| velocity 窗口靠每条交易到来时顺便清理。如果账户突然没有交易，窗口不会主动过期 | 改用 event-time 定时器（watermark 加 `registerEventTimeTimer`）主动清理 |
| velocity 每来一条交易就复制一次数组，复杂度 O(n) | 窗口小时没问题；窗口大就换成 `ListState` 或者环形缓冲区 |
| 阈值写死在代码里 | 用 Kafka 广播流动态下发规则配置，改阈值不用重启作业；第 8 阶段的 Spark 批处理基线也走这条路 |
| 本地 MiniCluster 重启会丢状态 | 生产环境把 checkpoint 写到 S3 或 HDFS，重新部署时用 savepoint 恢复 |
| 租户 ID 从 URL 路径取，没做认证 | 第 6 阶段接入 Keycloak，从 JWT 里取租户，并校验路径里的租户和 token 一致 |

### 实测数据（可以写进简历的素材）

- 90 秒内注入 8 个场景：5 个 geo jump、1 个大额、2 条坏消息。全部正确处理：6 条告警检出，2 条坏消息进 DLQ。
- 另外产生 1 条误报：模拟器随机生成的一笔正常消费约为平时的 8.9 倍，被大额规则标记。这条留作第 2 阶段调查 Agent 的测试样例，Agent 应该判断它大概率正常。
