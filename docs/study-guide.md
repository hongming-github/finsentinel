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

---

## 阶段 2：第一个调查 Agent

数据流：告警入库 → 工作队列领取 → Agent 调用工具取证 → 结构化报告 → 校验证据 → 落库。

### 必读

#### 1. [InvestigationTools.java](../agent-platform/src/main/java/com/finsentinel/platform/investigation/InvestigationTools.java)：最能体现 Agent 安全设计的文件

- **工具的访问范围在构造时就固定了**：每个案件新建一个工具实例，租户和账户在构造函数里定死。所有工具方法都没有 `tenantId` 或 `accountId` 参数，模型根本无从指定去查别的客户。
  - 可能被问："怎么防止 Agent 越权访问数据？"
  - 回答：不靠提示词里写"只能查本账户"，那挡不住 prompt injection。越权在代码结构上就做不到。
- **参数钳制**：`days` 和 `limit` 都用 `Math.clamp` 限定范围，上限来自配置 `finsentinel.investigation.tools.*`。模型传 10000 天也只会查配置的最大天数（默认 90 天），控制单次查询的成本和数据量。
- **精简视图**：返回给模型的是 `TxnView`，去掉了每行都一样的租户、账户、客户字段，时间只精确到秒。输入 token 因此少了约 17%。
- **由工厂创建**：`InvestigationToolsFactory.forCase(alert)`，调用方只需要指定是哪个案件。
- **审计**：每次工具调用都记录名字、参数、返回条数和耗时，连同报告一起存下来。
- **记录模型实际看到了什么**：`seenTxnIds` 收集所有工具返回过的交易 ID，后面用来核对证据。
- `getAccountProfile` 统计"正常行为"时，排除告警交易本身和告警之后的交易，见下面"踩过的坑"。
- `getCounterpartyHistory` 查这个对手方以前有没有往来，这是判断授权转账诈骗（APP scam）的关键信号。

#### 2. [LlmCaseInvestigator.java](../agent-platform/src/main/java/com/finsentinel/platform/investigation/LlmCaseInvestigator.java)、[CaseReport.java](../agent-platform/src/main/java/com/finsentinel/platform/investigation/CaseReport.java)、[CaseReportValidator.java](../agent-platform/src/main/java/com/finsentinel/platform/investigation/CaseReportValidator.java)，配合 [ADR 0006](adr/0006-agent-tools-and-output.md)

- 一次 `ChatClient` 调用：系统提示词、告警内容、工具，加上 `ENABLE_NATIVE_STRUCTURED_OUTPUT`。
- **结构化输出**：`CaseReport` 这个 record 会被转成 JSON Schema，让 OpenAI 按 schema 输出。字段上的 `@JsonPropertyDescription` 本身就是提示词的一部分。
- **内容校验**：`CaseReportValidator` 检查 schema 管不到的东西，包括分数范围、结论和分数是否一致、各部分不能为空，以及引用的交易 ID 是否真的由工具返回过。实测抓到过模型把商户 ID `m-1045` 当成交易 ID 引用。
- **修复循环**：校验不通过时，把具体问题列给模型，让它重答，最多 `max-repairs` 次（默认 1 次）。还是不合格就让这次调查失败，交给队列重试或转人工。**不合格的报告永远不会入库。**
- `CaseInvestigator` 接口把工作队列和 LLM 隔开。测试时换成桩实现，平时跑测试不花一分钱。

#### 3. [InvestigationRepository.java](../agent-platform/src/main/java/com/finsentinel/platform/investigation/InvestigationRepository.java) 和 [InvestigationWorker.java](../agent-platform/src/main/java/com/finsentinel/platform/investigation/InvestigationWorker.java)，配合 [ADR 0004](adr/0004-investigation-queue.md)

这组文件是分布式系统面试的好素材，要能讲清楚：

- **`FOR UPDATE SKIP LOCKED`**：多个实例同时领取任务时，不会领到同一条，也不会互相等锁。
- **按严重级别排序**：HIGH 优先领取。
- **超时回收**：领取后超过 5 分钟还没完成，就当作 worker 已经崩溃，任务可以被重新领取。
- **防过期令牌（fencing token）**：`claimed_at` 同时充当令牌。写结果时要求 `claimed_at` 必须和领取时一致，一个卡住后超时的旧 worker 就无法覆盖新 worker 的结果。`workerThatLostItsClaimDoesNotWriteAResult` 这个测试专门验证这一点。
- **完成是原子的**：`complete()` 在同一个事务里更新状态并写入报告。
- **并发上限**：每次领取的 `batchSize` 就是本实例同时进行的 LLM 调用数上限。
- **成本熔断**：`max-per-hour` 限制每小时最多调查多少条，来历见"踩过的坑"第 3 条。

#### 4. [TransactionStoreListener.java](../agent-platform/src/main/java/com/finsentinel/platform/transaction/TransactionStoreListener.java)

- 批量消费加 `batchUpdate` 批量写入，并用 `on conflict do nothing` 保证幂等。
- 这是一个独立的消费组，和告警消费互不影响。
- 坏消息在这里直接跳过，因为 Flink 那边已经把它们送进 DLQ 了，不用重复处理。

#### 5. [LiveInvestigationTest.java](../agent-platform/src/test/java/com/finsentinel/platform/investigation/LiveInvestigationTest.java)

三个真实 LLM 案例，也是第 7 阶段评估集的雏形。断言写的是**方向**，不是精确值：比如"不能判成正常"，而不是"必须 96 分"。原因是 LLM 的输出不确定，测试只能约束它不犯方向性错误。

### 踩过的坑（面试时最有说服力的部分）

1. **基线污染**：`getAccountProfile` 最初把告警交易本身也算进"正常行为"里，报告因此写出"90 天里有一笔 GB 刷卡"，而那一笔恰恰就是告警交易。修复是统计时只看告警之前的交易，并排除告警交易。这和 Flink 规则里"被标记的金额不进基线"是同一个道理，给 Agent 的工具同样要遵守。
2. **漏判，根因在工具不在模型**：一笔约为平时 28 倍的大额转账被判成"正常"，理由是"之后还在正常刷卡"。真正缺的是"这个收款人以前转过吗"这个信号。模拟器每笔交易都随机挑商户，这个信号本来就不存在，Agent 也没有工具能查。
   - 修复：模拟器给每个账户设置固定的常用商户和收款人；新增 `getCounterpartyHistory` 工具；提示词补充授权转账诈骗的判断规则。
   - 结果：同一个案例从 LIKELY_LEGITIMATE（18 分）变成 NEEDS_REVIEW（78 分）。
   - 面试可以这样讲：先看 Agent 犯了什么错，判断根因是缺数据、缺工具还是提示词不够，再对症修，最后用真实 LLM 测试确认修好了。
3. **告警风暴差点变成账单**：改了模拟器的随机种子之后，同一个账户 ID 在两次运行里变成了"两个人"，一个常驻 SG，一个常驻 MY，几秒内冒出约 640 条 GEO_JUMP 告警。
   - 当时队列会把它们逐条送给 LLM。手动停下时已经调查了 31 条，还有 637 条在排队。
   - 修复一：账户属性改用固定种子生成，每次运行只有交易流是随机的。
   - 修复二：加上每小时调查上限作为成本熔断。
   - 教训：**上游出错不能直接变成下游无上限的开销**。任何调用 LLM 的路径都必须有预算。
4. **Spring AI 和 OpenAI 接口的限制**：`gpt-5.4-mini` 在 Chat Completions 接口里不允许同时使用工具和 reasoning，这个组合只有 Responses 接口支持。而 Spring AI 2.0 的 OpenAI 模块只实现了 Chat Completions，所以设了 `reasoning-effort: none`。这个任务主要靠工具查数据，不太需要深度推理，这样速度和成本反而更好。

### 可以主动讲的不足和改进方向

| 不足 | 改进方案 |
|---|---|
| 单个 Agent 包办一切，每个案件都用同一个模型 | 第 4 阶段拆成分诊、调查、复核三个 Agent；第 5 阶段按严重级别路由到不同模型 |
| 修复调用时不带上一轮的工具结果，模型可能要重新调用工具 | 修复很少发生，暂时可以接受。如果 `repaired_issues` 显示修复变得频繁，就改成在原对话里继续修复 |
| geo jump 的"回程"也会单独报一次告警 | 第 4 阶段把同一账户短时间内的多条告警合并成一个案件 |
| 成本上限是全局的 | 第 5 到第 6 阶段改成按租户分配预算，并记录每个案件的花费 |
| 工具直接查主库 | 数据量大了以后，查询改走只读副本或专门的特征存储 |
| 轮询方式会带来最多 5 秒的延迟 | 对分析师的工作流来说可以接受。真要更实时，可以在告警入库后主动通知 worker 来领取 |

### 实测数据

- 两轮模拟（换了种子）一共注入 11 个欺诈场景，全部检出、全部完成调查。正常流量没有产生误报。
- 每个案件耗时 4 到 7 秒，用 6k 到 11k token（`gpt-5.4-mini`），通常调用 5 个工具。
- 三个真实 LLM 案例全部通过：跨国盗刷判为 LIKELY_FRAUD（92 分），本国正常大额消费判为 LIKELY_LEGITIMATE（18 分），大额转账给新收款人判为 NEEDS_REVIEW（78 分）。

### 设计取舍问答（面试官很可能会问的）

**问：这个项目里 Agent 的价值在哪？不用 Agent 会怎么做？**
- 不用 Agent 有两种做法：
  - 人工调查：分析师在几个系统之间来回切换，每条告警十几分钟以上，而绝大多数告警最后是误报。
  - 确定性自动化：把基线、对手方是否新出现、历史告警这些特征预先算好，交给评分卡或机器学习模型打分，再用模板拼出说明文字。
- 实话说，按第二阶段的范围，第二种做法能完成大部分工作，而且更便宜、更稳定。证据是日志里每个案件都是 `tools=5`，Agent 每次都把 5 个工具全调了一遍，"自主决策"其实没发挥出来。
- Agent 真正的价值在这些地方：
  - 读非结构化信息：CRM 备注、KYC 文件、监管条文。
  - 每个案件的调查路径都不一样，没法写死成流水线。
  - 写监管要求的叙述性报告，比如可疑交易报告（STR）。
  - 应对规则还没覆盖到的新模式。
- 合理的架构是混合式：**规则和模型负责检测、打分，Agent 负责取证、写报告，最终由人来决定。**

**问：为什么不用 JPA？** 见 [ADR 0005](adr/0005-sql-first-persistence.md)。
- 这里的查询几乎都用到 Postgres 的特有功能：`SKIP LOCKED`、`ON CONFLICT`、`percentile_cont`、数组、jsonb。换成 JPA 也只能写成 native query，却要多背持久化上下文、脏检查这些用不上的复杂度。
- 我们的类型是不可变的 record，而 JPA 要求实体是可变的。
- 手写 SQL 的代价是编译期发现不了错误，这一点靠 Testcontainers 集成测试兜底，每条 SQL 都会被执行到。
- 项目再大一些我会选 jOOQ，SQL 写错在编译期就能发现。案件管理这类典型的增删改查，适合用 Spring Data JDBC。

**问：工具为什么用构造函数注入租户，而不用 Spring AI 的 `ToolContext`？**
- `ToolContext` 完全可以用，它是一个 `Map<String, Object>`，而且不会暴露给模型。
- 选构造函数的理由：
  - final 字段在编译期就保证必须传入，不可能漏掉；Map 要用字符串 key 去取，写错 key 到运行时才会报错。
  - 每个案件本来就有自己的状态（调用记录、`seenTxnIds`），本来就需要每个案件一个实例。
- 代价是每个案件都要新建一个对象，再反射扫描一遍工具方法。和动辄几秒的 LLM 调用比，这点开销可以忽略。

**问：把整个工具对象挂上去，用不到的工具会不会浪费上下文？**
- 会。每一轮对话都会重复发送所有工具的描述和参数 schema。
- 但在这个项目里，真正占上下文的大头是**工具返回的结果**。精简返回字段后，输入 token 少了约 17%（从约 5.95k 降到约 4.95k）。
- 另外，系统提示词加工具定义是固定不变的前缀，OpenAI 会自动缓存，实测每个案例有 1152 个 token 命中了缓存。
- 工具多了以后的办法：
  - 每个 Agent 只挂它需要的工具（第 4 阶段）。
  - 按告警类型动态选择要挂的工具。
  - 工具太多时，先检索出相关的几个再挂上去。

**问：提示词里没写 CaseReport 的结构，模型不按格式回复怎么办？**
- 开启原生结构化输出后，`CaseReport` 会被转成 JSON Schema，以 strict 模式发给 OpenAI。strict 模式靠**约束解码**：生成时不符合 schema 的 token 根本不会被选中，所以格式错误从机制上就排除了。
- 字段上的 `@JsonPropertyDescription` 会写进 schema，相当于每个字段各自的提示词。
- 不开这个选项时，Spring AI 会把格式说明当普通文字加进提示词，模型才有可能不照做。
- 格式有保证不等于内容对：
  - 编造证据、结论和分数矛盾，靠 `CaseReportValidator` 加修复循环处理。
  - 模型拒答或输出被截断，抛异常后由队列重试。
  - 以后要是换成不支持 strict schema 的模型，就退回到"提示词描述格式 + 同一个校验器"的方式。

**问：配置怎么管理？**
- Spring 这边，所有可调的参数都放在 `application.yaml` 的 `finsentinel.*` 下面，用 `@ConfigurationProperties` 的 record 绑定，并在 record 的构造函数里校验，配置非法时应用直接启动失败。
- Flink 作业不依赖 Spring，参数放在 `stream-job.properties` 里，可以用 `--key=value` 覆盖。写了不存在的配置项会直接报错，防止拼写错误时悄悄用回默认阈值。
- 更进一步是第 8 阶段的规则动态下发：通过 Kafka 广播流推送阈值，修改阈值不用重启作业。

### 第二轮改进中踩的坑

1. **嵌套三元表达式导致的空指针**：`a == null ? b : b == null ? a : a + b`，因为 `a + b` 是 `int`，整个表达式被当成 `int` 处理，`a` 为 null 时取 `b` 就会对 null 拆箱，直接报空指针。这个 bug 是用"假模型"测试修复流程时发现的：缓存 token 数经常是 null，线上只要触发一次修复调用就会崩溃。
2. **改提示词引发回归，以及 LLM 结果的不确定性**：
   - 为了解决转账诈骗加的一句"之后正常消费不能说明交易正常"，被模型套用到了刷卡消费上，结果把本国正常大额消费判成了欺诈。
   - 同一份提示词，上午跑是"正常"，下午跑是"欺诈"，说明这个案例正处在判断边界上。
   - 修复方法：写清楚每条规则适用的交易类型（刷卡 vs 转账），然后**连续跑 3 轮**，9 次判断全部通过才算修好。
   - 教训：只跑一次 LLM 测试说明不了什么。第 7 阶段要按"每个案例跑 N 次，统计通过率"来评估。
