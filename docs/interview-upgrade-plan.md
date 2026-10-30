# RAG知识库面试题对齐升级计划

来源：2026-10-06 面试文章《RAG知识库 RAG + PaiCLI 面试 9 问》（toBeBetterJavaer `docs/src/sidebar/itwanger/ai/paismart-paicli-mianshi.md`）。文章按面试官期望的方案作答，下面是当前代码（HEAD `c168e269`）和文章答案之间的差距，做完一项勾一项。路径相对 `src/main/java/com/yizhaoqi/smartpai/`。

进度：12 / 12 已完成（2026-10-06），验证情况和遗留问题见文末。

## P0：数据正确性

- [x] **上传消费路径幂等**（文章 01 题、07 题）
  - 现状：`FileProcessingConsumer` 的 UPLOAD_PROCESS 路径解析前不清理旧数据，`document_vectors` 没有唯一键，Kafka 重复投递会插入重复分块并重复调用 Embedding；只有 REINDEX 路径（`DocumentService.java:192-211`）会先删除
  - 目标：消费开头查询向量化状态，已完成直接跳过；未完成先按 fileMd5 删除 ES 文档和 `document_vectors`，再解析；`document_vectors` 加 `UNIQUE(file_md5, chunk_id)`，同步 `docs/databases/ddl.sql`
  - 完成：`FileProcessingConsumer` 先经 `VectorizationLeaseService.claim` 认领：上传任务已 COMPLETED 直接跳过，别的消费者心跳未超时也跳过。认领后 `DocumentService.clearIndexedData` 清 ES 别名和 `document_vectors`，再解析、向量化。上传和重建两类任务走同一条流水线。文件优先从 MinIO 合并对象读，不再依赖 1 小时就过期的预签名 URL。`DocumentVector` 加唯一键 `uk_document_vectors_md5_chunk`，ddl 已同步，老库去重见 `docs/databases/migrations/2026-10-06-file-processing-reliability.sql`
- [x] **分片数据按用户隔离**（文章 02 题相关）
  - 现状：`chunk_info` 唯一键是 `(file_md5, chunk_index)`，MinIO 分片路径是 `chunks/{fileMd5}/`，都不含 userId；`file_upload` 和 Redis bitmap 却按用户区分。两个用户同时上传同一个文件时，一方合并后会删掉另一方的分片（`UploadService.java:612`）
  - 目标：分片记录和分片对象路径都带上 userId，或者改成同 MD5 秒传共享并加引用计数
  - 完成：选了按用户隔离。`chunk_info` 加 `user_id`，唯一键改为 `(user_id, file_md5, chunk_index)`；对象路径改为 `chunks/{userId}/{fileMd5}/{chunkIndex}`；`ChunkInfoRepository` 的查询和删除全部带 userId，合并后只删自己的分片。老库的旧唯一键由启动时的 `config/LegacySchemaMigration` 删除并回填 user_id（只在 MySQL 上执行，可重复执行）
- [x] **合并完成状态改成条件更新**（文章 02 题）
  - 现状：`mergeChunks` 最后写 COMPLETED 用的是普通 `save()`（`UploadService.java:622-629`）
  - 目标：用 `updateStatusIfCurrent(MERGING → COMPLETED)`，影响行数为 0 时按状态已变化处理
  - 完成：`FileUploadRepository.completeMergeIfMerging`。影响行数为 0 时重读状态，已完成按幂等成功处理，其他状态返回 409 且不删分片。顺序改成"条件更新成功后再清理分片"，状态没改成功时分片还在，用户可以重新合并

## P1：可靠性

- [x] **卡住任务的发现和恢复**（文章 07 题）
  - 现状：没有任何 `@Scheduled` 任务；向量化状态停在 PROCESSING、上传状态停在 MERGING 时没人处理；没有重试次数字段
  - 目标：`file_upload` 加 `processing_started_at`、`heartbeat_at`、`retry_count`；消费者每处理完一批分块更新心跳；定时任务扫描心跳超时的 PROCESSING 记录，用带心跳时间的条件更新改回待处理并重新投递；超过重试上限（如 3 次）标记失败并告警
  - 完成：三个字段已加，另加了扫描用的索引。`processing_started_at` 同时是认领凭证：心跳、完成、失败都带它做条件更新。任务被改回待处理后，旧消费者下一次心跳失败（`VectorizationLeaseLostException`）并主动放弃，不会和新消费者同时写。`VectorizationService` 每批分块写完 ES 就回调一次心跳。`service/reliability/FileProcessingRecoveryJob` 每分钟扫描一次：心跳超时（默认 10 分钟）的 PROCESSING 记录，用"扫描时看到的心跳"做条件更新改回 PENDING、`retry_count + 1`，同一事务写 outbox 重新投递；超过 `max-retries`（默认 3）的标记 FAILED 并打 `[ALERT]` 日志。MERGING 超时（默认 10 分钟）改回上传中。参数在 `file.processing.reliability.*`
- [x] **废弃上传的定时清理**（文章 01 题）
  - 现状：用户传了一半放弃后，MinIO 分片、`chunk_info`、Redis bitmap 永久残留；bitmap 没有设置过期时间（`UploadService.java:305-306`）
  - 目标：定时清理长时间停在上传中的记录及其分片；bitmap 创建时设置过期时间
  - 完成：每个分片到达时刷新 `heartbeat_at`。恢复任务对超过 24 小时没有新分片的上传先条件删除记录（扫描后心跳变了就不删），删成功再清理 MinIO 分片（按记录和按前缀各删一遍）、`chunk_info` 和 bitmap。bitmap 每次写入续期 48 小时
- [x] **Kafka 投递可靠性**（文章 01 题）
  - 现状：合并成功写 COMPLETED 后才发 Kafka 消息，发送失败时向量化状态停在 PROCESSING 且没有消息；消费端没有设置 `isolation.level=read_committed`，生产端事务对消费者不起作用；死信队列没有消费者
  - 目标：改成 outbox 表或发送失败回滚状态；消费端设置 read_committed；死信队列至少落库并告警
  - 完成：新增 `file_processing_outbox` 表和 `service/reliability/FileProcessingOutboxService`。合并接口和"重试向量化"把状态改成 PENDING、写 outbox 在同一事务里提交，提交后异步投递；失败按指数退避加抖动补发（5 秒一轮，多实例靠条件认领互斥），多次失败打 `[ALERT]`。消息 key 改为 fileMd5，同一文件落在同一分区。消费端设置 `isolation.level=read_committed`，生产端 `max.block.ms=10s`。新增 `file_processing_dead_letter` 表和 `consumer/FileProcessingDeadLetterConsumer`，按死信在主题里的位置幂等落库并告警；它用独立的监听器工厂，落库失败不会再转发回死信主题

## P1：限流和多供应商

- [x] **全局限流改成 Lua 令牌桶**（文章 06 题）
  - 现状：`RateLimitService.java:98-113` 用 INCR + EXPIRE 两条命令做固定窗口，不是原子操作，进程在两条命令之间崩溃会留下永不过期的计数 key
  - 目标：Redis Lua 脚本实现令牌桶（剩余令牌数 + 上次补充时间，时间取 Redis TIME）；模型调用按 QPS 限流；超出的请求进有界等待队列，带截止时间，在线问答优先于后台向量化
  - 完成：`resources/scripts/token_bucket.lua` + `service/TokenBucketRateLimiter`，补充、扣减、写回、PEXPIRE 在一个脚本里原子执行。注册、登录、聊天、Embedding 查询的窗口限流都换成令牌桶（key 前缀换成 `ratelimit:tb:`，旧计数 key 自然过期）。`client/resilience/ModelCallRateLimiter` 按供应商做 QPS 令牌桶：拿不到令牌进有界等待队列（在线 200 / 后台 50），超过截止时间（在线 5 秒 / 后台 60 秒）拒绝；后台请求要给在线请求留 30% 余量，桶快空时只有在线请求拿得到。上传向量化、上下文生成、冲突确认、离线评测标记为 BACKGROUND。参数在 `rate-limit.model-qps.*`
- [x] **模型调用的超时、重试、熔断和切换**（文章 09 题）
  - 现状：`LlmProviderRouter` 流式调用没有超时和重试（`:70-76`、`:238-244`）；每次调用都新建 WebClient，没有配置连接池；没有熔断，也不会自动切换供应商，管理员手动切换
  - 目标：共享 WebClient 并按供应商配置连接池和连接、读取、总超时；Resilience4j 按供应商配置熔断器和舱壁；熔断打开后大模型切到备用供应商；重试只放在客户端一层，指数退避加抖动、遵守 Retry-After，已输出内容后不重试；加重试预算（重试占比超过 10% 停止重试）；后端按用户和会话生成请求幂等键，重复点击复用前一个请求的结果流
  - 完成：新增 `client/resilience` 包。
    - `ModelWebClientFactory`：按 scope:provider 共享连接池，连接超时 5 秒、读超时 60 秒；改了地址或 Key 按指纹重建客户端
    - `ModelCallGuard`：由外到内依次是熔断器 → 舱壁 → 单次尝试总超时（从订阅开始计时，覆盖整个流）→ QPS 令牌桶 → HTTP 调用
    - 重试：`ModelRetryPolicy` 只重试网络错误、超时、408、429、5xx，指数退避加抖动；有 Retry-After 时按它等，超过 30 秒不重试；收到第一条流式数据后不重试。`RetryBudget` 用 10 秒滑动窗口，重试超过请求数的 10% 就停，低流量保底 3 次
    - 切换：`LlmProviderRouter` 三个调用入口都走 `withFailover`。主供应商熔断打开、舱壁满、限流超时或重试耗尽，且还没输出内容时，按 `ModelProviderConfigService.getLlmFailoverChain` 切到备用供应商，并换成它自己的模型名
    - 幂等：键为 `chat:request:{userId}:{sha256(会话+问题)}`，在 `ChatGenerationStateService.claimRequest`。生成中重复提交会直接挂到进行中的生成上，start 消息带上已生成的内容（前端 `input-box.vue` 据此补齐），不重复调模型，也不占限流额度
    - 参数在 `model-call.*`
- [x] **Embedding 重试改成指数退避**（文章 09 题相关）
  - 现状：`EmbeddingClient.java:147-156` 是固定 1 秒间隔重试 3 次
  - 目标：指数退避加抖动，429 时遵守 Retry-After
  - 完成：`EmbeddingClient` 改走 `ModelCallGuard`，和 LLM 共用一套重试、熔断、舱壁、QPS。embedding 不切换供应商：不同模型的向量不能混用

## P2：清理

- [x] `application.yml:107-116` 的 `rate-limit.llm-request`、`rate-limit.embedding-batch` 没有绑定到任何配置类，删除或接入 `RateLimitProperties`
  - 完成：`application.yml`、`application-dev.yml`、`application-docker.yml` 三处都删了，换成绑定到 `RateLimitProperties.ModelQps` 的 `rate-limit.model-qps`
- [x] `config/WebClientConfig.java` 里的 `embeddingWebClient` bean 没有被使用，和上面的共享 WebClient 一起处理
  - 完成：删除 `WebClientConfig.java`，改由 `ModelWebClientFactory` 统一提供

## 验证记录（2026-10-06）

- `mvn test`：301 个用例，0 失败，8 个错误。这 8 个都是 Spring 上下文加载用例，改动前同样失败：`.env` 里 MinIO 密钥写的是 `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY`，而 `.env` 激活的 dev profile 读的是 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`，测试时取到空值。传入密钥后，`SmartPaiApplicationTests` 能在 H2 上完整启动，新增的 Bean、JPQL、定时任务、DLT 监听器都能装配
- 新增测试：
  - `FileUploadRepositoryReliabilityTest`：在 H2 上实际执行所有条件更新
  - `LlmProviderRouterFailoverTest`：两个本地 HTTP 服务模拟主备供应商，走真实 WebClient
  - 其余：`FileProcessingConsumerIdempotencyTest`、`VectorizationLeaseServiceTest`、`FileProcessingRecoveryJobTest`、`FileProcessingOutboxServiceTest`、`ModelCallGuardTest`、`ModelRetryPolicyTest`、`RetryBudgetTest`、`ChatRequestIdempotencyTest`
  - `UploadServiceTest` 里两个改动前就失败的旧断言，已按现有逻辑更新
- Lua 令牌桶在本机 Redis 上用 `redis-cli --eval` 验证过突发量、等待时间和后台预留
- 迁移脚本和新版 `ddl.sql` 在本机 MySQL 8.0 的临时库上执行通过，验证完已删除临时库。顺手修了 `ddl.sql` 里 `user_token_record.request_count` 行缺逗号的问题，它会让整份脚本执行失败
- 本机真实链路（2026-10-07，后端跑在 8082，连 MySQL `RAG知识库1`、Redis、Kafka、ES、MinIO，测试账号和数据验证完已删除）：
  - 升级前 `RAG知识库1` 里有一个文件的 7 个分块各存了 5 份（1 次投递 + 4 次 Kafka 重试），正是 P0 要修的问题；按迁移脚本去重 28 行后启动，新列、新表、`chunk_info` 和 `document_vectors` 的唯一键全部生效
  - 上传 → 合并 → outbox 投递 → 认领 → 清理 → 解析 → 向量化：分片落在 `chunks/{userId}/{md5}/0`，合并后分片记录清空，outbox 一次投递成功，MySQL 和 ES 各 2 个分块
  - 重复投递：同一任务再投一次，消费者日志"文件已向量化完成，跳过重复投递"，没有再调 embedding
  - 卡住恢复：把文件改成心跳 30 分钟前的 PROCESSING，恢复任务改回待处理并重新投递，`retry_count = 1`，重新处理后仍是 2 个分块，ES 无残留
  - 重复提问：两个 WebSocket 连接相隔 300ms 发同一问题，拿到同一个 generationId，第二个带 `resumed: true`，只生成一次；回答正确引用了刚上传的文件
  - 废弃上传：上传一个分片不合并，心跳改成两天前，恢复任务删除了记录、分片记录、MinIO 分片和 bitmap（bitmap 写入时 TTL 为 48 小时）
  - 没在浏览器里点：8081 被 toBeBetterJavaer 的 vuepress 占着，前端代理指向 8081，这次用接口和 WebSocket 脚本验证；前端 `input-box.vue` 的改动只过了 eslint
- 升级后 `RAG知识库1.chunk_info` 里还有 30 条 user_id 为空的旧分片记录，没有对应的上传中记录，是改动前废弃上传留下的，恢复任务不会处理，可以手动删除

## 遗留和注意

- 已有数据库升级：应用启动时 `ddl-auto=update` 加列建表，`LegacySchemaMigration` 删除 `chunk_info` 旧唯一键并回填 user_id。`document_vectors` 有重复数据时唯一键建不上，启动日志会提示先执行迁移脚本去重
- Kafka 消费失败仍由 `DefaultErrorHandler` 重试 4 次，和模型客户端的重试叠加后，最坏一批分块会调 5 × 3 次模型。任务级重试兜的是解析、ES 这类非模型错误；要做到严格只在客户端一层重试，需要让消费者对模型调用失败不再做任务级重试
- `DocumentService.deleteDocument` 仍按 fileMd5 删除所有用户的记录、合并对象和 ES 数据，同一个文件多用户共享时，一方删除会影响另一方。这和这次的分片隔离是两回事，没有改
- `DeepSeekClient`、`RerankClient`、`ModelProviderConfigService.testConnection` 还是各自新建 WebClient，不在这次范围内

## 上线后修复（2026-10-07）

第一次发布后，恢复任务把生产上 29 个升级前就卡在 PROCESSING 的历史文件（4 月到 9 月）全部重新投递，其中大文件每次 LiteParse 解析约 4 分钟，又因为上传者 Embedding 余额不足失败，Kafka 整份重试 5 次，单消费线程被占住约半小时，新上传排在后面。积压最终自行排空（15 个处理成功，14 个余额不足失败并进入死信表），没有改动生产数据。随后做了三处修复：

- 恢复任务只接管新代码认领过的任务：没有 `processing_started_at` 的 PROCESSING 记录视为升级前遗留，直接标记 FAILED（"升级前中断的历史任务，已停止自动重试，如需要请手动重试向量化"），不再自动重投
- 余额不足改为不可重试：新增 `InsufficientBalanceException`（继承 `RateLimitExceededException`，聊天链路仍返回 429），消费者遇到它直接标记失败并提交消息，不走 Kafka 重试和死信；上下文生成、embedding 重建的限流重试也跳过它
- 知识库页面改为推送：认领、完成、失败、投递、卡住恢复等状态变化后，`DocumentStatusNotifier` 在事务提交后把最新状态发到 Redis 频道 `paismart:document-status`，每个实例订阅后通过聊天 WebSocket 推给文件上传者打开的所有页面（`ChatSessionRegistry` 改为记录用户的全部连接）。前端收到 `document_status` 事件直接更新行；原来 3 秒一次的轮询改为兜底，WebSocket 正常时 30 秒、断开时 5 秒，重连后整体刷新一次
- 本地验证：正常上传推送 PENDING → PROCESSING → COMPLETED 约 1.6 秒；余额不足只认领 1 次、无死信；历史卡住任务 8 秒内被标记失败且未重投；浏览器里点"重试向量化"后行状态由推送更新，期间没有轮询请求，控制台无错误

## 第二轮：海康 Agent 面试 7 问（2026-10-09）

来源：toBeBetterJavaer `docs/src/sidebar/itwanger/ai/haikang-robot-agent-mianshi.md`，对照 HEAD `521369a8`。

- [x] **问答请求级截止时间**（文章 02 题）
  - 现状：ReAct 每一轮各自有 120 秒超时（`service/ChatHandler.java:49,438`），4 轮加最终回答最坏要等好几个 120 秒；检索、重排序的超时也是各自固定配置，互相不知道整个请求还剩多少时间
  - 目标：请求入口生成一个截止时间，传给 ReAct 循环、检索和重排序；每一步的超时取自身配置和剩余时间中较小的值；剩余时间不够再调用一轮工具时，复用 `ChatHandler.java:303-306` 那条“不要再调用工具，直接回答”的消息提前收尾；剩余时间不够时跳过重排序，直接用 RRF 结果
  - 完成：新增 `utils/RequestDeadline`（绑定在当前线程，可嵌套，内层只能更早）。`ChatHandler.processMessage` 收到消息时按 `chat.deadline.total-seconds`（默认 120 秒）算出截止时间，ReAct 循环所在线程打开作用域：
    - 每轮模型调用的等待上限 = min(单轮 120 秒, 请求剩余时间)
    - 工具执行在内层作用域里跑，截止时间再减去最终回答预留（默认 30 秒），检索慢了也挤不掉回答的时间
    - ES 双路召回的等待、查询向量的单次超时和整体等待、重排序超时都取自身配置和剩余时间的较小值；剩余时间低于 `search.hybrid.rerank.skip-below-millis`（默认 2 秒）时跳过重排序，直接按 RRF 返回
    - 从第二轮起，剩余时间不够"一轮工具调用（默认 15 秒）+ 最终回答预留"时，复用那条"不要再调用工具，直接回答"的消息提前收尾。第一轮总是带工具：知识库优先，至少要检索一次（一开始实现成第一轮也检查，本地用 40 秒预算实测时模型一次没检索就回答"暂无相关材料"，所以改掉）
    - 验证：`ChatHandlerDeadlineTest` 走真实 `processMessage` → `LlmProviderRouter` → 本地 HTTP 模型服务，断言时间够时第二轮仍带工具、不够时第二轮不带工具并带收尾消息；`HybridSearchServiceTest` 断言剩余 1.5 秒时不调用重排序；本地后端用 40 秒预算实测：第一轮检索、第二轮提前收尾，7.5 秒给出带引用的回答；默认 120 秒预算下 9.2 秒正常回答
    - 未覆盖：`generate_summary` 工具里 `DeepSeekClient` 的摘要流仍按自身超时，它的输出直接流给用户、作为最终回答，没有接入请求截止时间
- [x] **显式配置 Kafka 拉取参数**（文章 06 题）
  - 现状：`config/KafkaConfig.java:110-120` 没有设置 `max.poll.records` 和 `max.poll.interval.ms`，用的是客户端默认值（500 条、5 分钟）；LiteParse 单个文件最长 300 秒，再加上信号量最多等 120 秒，一条消息的处理时间可能超过 5 分钟，触发再均衡和重复消费（有租约认领兜底，但会白白浪费一次解析）
  - 目标：`max.poll.records=1`；`max.poll.interval.ms` 大于最长处理时间，建议和心跳超时 `file.processing.reliability` 的 10 分钟对齐
  - 完成：`KafkaConfig.consumerFactory` 设置 `max.poll.records=1`，`max.poll.interval.ms` 直接取 `file.processing.reliability.vectorization-heartbeat-timeout-seconds`（默认 10 分钟），两者改一处就同步；启动时如果 LiteParse 超时 + 等待解析许可（默认 300 + 120 秒）不小于这个间隔会打 WARN。`KafkaConsumerSettingsTest` 断言配置值，本地后端用新配置正常启动和消费（Kafka 客户端的配置打印被 logback 里 `org.apache.kafka` 的 WARN 级别屏蔽，没能从日志里直接看到生效值）


## 第三轮：字节 Agent 面试 9 问（2026-10-13）

来源：toBeBetterJavaer `docs/src/sidebar/itwanger/ai/agent-mianshi-zijie-compiler.md`，对照 HEAD `0e005a9f`。文章的 02、03、04 三题讲检索打分，答案按面试官期望的方案写，以下是和源码的差距。

进度：5 / 5 已完成（2026-10-13，未发布）。测试 335 个全部通过（需映射 MinIO 密钥）；本地后端只跑检索的评测 10 轮，聊天实测引用带上了 `score` 与 `rawScore`。评测报告在 `eval-reports/`（不进 git），文件名带 `r3-` 前缀。

- [x] **单路召回失败时保留另一路结果**（文章 02 题）
  - 现状：`HybridSearchService.java:145` 用 `CompletableFuture.allOf(...).get(timeout)` 等两路，任一路抛异常或超时就整体失败，退到 `TEXT_ONLY`（`:105-107`），另一路已经拿到的结果被丢掉
  - 目标：两路各自 `handle` 成异常安全的结果，至少一路成功就用成功的那一路继续走 RRF 和重排序，返回时在模式里标明只有一路召回；两路都失败才退到纯文本检索
  - 完成：`HybridSearchService.hybridSearch` 两路共用一个等待截止时间、各自等待，超时或异常只让这一路失败；至少一路成功就继续 RRF 和重排序，模式标为 `BM25_ONLY` / `KNN_ONLY`（重排序不可用时再加 `_RRF` 后缀），两路都失败才退到 `TEXT_ONLY`。查询向量生成失败也按"KNN 这一路失败"处理，不再直接退到纯文本检索。聊天引用和前端的检索方式文案同步了新模式
- [x] **RRF 兜底路径补上分数阈值**（文章 04 题）
  - 现状：`search.hybrid.rerank.score-threshold`（默认 0.1）只在重排序成功时生效（`HybridSearchService.java:183-187`）；跳过重排序或重排序失败时走 `rrfTopK`（`:169-175, 191-201`），直接按 RRF 顺序截取 topK，不做任何过滤，噪声会被交给大模型
  - 目标：给 RRF 模式单独配一个阈值，按 RRF 分数的理论下界（召回窗口 150 时约 0.0048）和评测结果定值；只被单路召回且名次靠后的结果过滤掉，宁可返回空也不要塞不相关的文档
  - 完成：新增 `search.hybrid.rrf-score-threshold`，跳过或重排序失败时先按阈值过滤再截取 topK；阈值按两路量纲配置，只有一路召回时自动减半（单路分数上限只有一半），"平均名次"的含义保持一致
  - 定值：用新加的 `scripts/eval_rrf_threshold.py` 拿评测报告里的 RRF 候选离线模拟。结论和预想不一样：RRF 前 5 名几乎全是两路共同召回的（原始分 0.026 到 0.033，单路最多 0.0164），"只被单路召回、名次靠后"在前 5 名里基本不出现，0.0164 以下的阈值什么都过滤不掉；相关分块最低 0.029，和不相关、无答案题的分数大面积重叠。最终取 0.028（约等于两路平均名次 11 名以内）：分块级 Recall@5 不变（80.4%），无答案题平均返回从 5 条降到 4 条；超过 0.030 召回率开始明显下降
  - 实测（关掉重排序跑评测）：RRF 兜底 Recall@5 80.4%、MRR 0.59，无答案题返回 5 / 4 / 2 / 5 条，和离线模拟一致。顺带发现：这个评测集上 RRF 顺序的分块级命中率（80.4%）比重排序后（76.1%）还高，gte-rerank-v2 对口语化问句的排序还有提升空间
- [x] **分数出口统一映射到 0 到 1**（文章 04 题）
  - 现状：`SearchResult.score` 三种模式量纲不同，重排序分约 0 到 1、RRF 分约 0.0048 到 0.0328、`TEXT_ONLY` 的 BM25 原始分无上界，都原样写进给大模型的文本（`AgentToolRegistry.java:290-291`）和引用映射（`ChatHandler.java:1108`）
  - 目标：出口按各自的理论上界做一次固定换算（RRF 分除以 0.0328，重排序分原样），不用 min-max（分母随当批结果变化，跨查询不可比）；返回体里保留原始分和模式，便于排查
  - 完成：`HybridSearchService.normalizeScore` 在出口统一换算：重排序分原样；RRF 分除以两路都排第一时的上界 2/(k+1)；`TEXT_ONLY` 的 BM25 分没有上界，按 score / (score + 10) 做固定的饱和换算（`search.hybrid.bm25-score-saturation`）。`SearchResult` 新增 `rawScore`，聊天引用映射（含持久化）和前端类型都带上 `score` 与 `rawScore`。`SearchResult.withParentContext` 复制父块时原来会丢掉新字段，本地聊天实测时发现，已修复并补了用例
- [x] **文件名参与关键词打分**（文章 03 题）
  - 现状：ES 索引里没有文件名字段，文件名是检索完成后按 fileMd5 从 MySQL 关联出来的（`HybridSearchService.java:403-418`），所以不参与 BM25 打分；关键词查询是单字段 `match`（`:234-239`）
  - 目标：建索引时把文件名写成独立字段，查询改成多字段匹配并给文件名更高权重，让《向量检索设计》这类标题性强的文档在对应提问下排得更前；同时评估 `minimum_should_match` 和短语加分对口语化长问句的影响
  - 完成：mapping 模板新增 `fileName`（IK 分词），入库和 embedding 重建都写入文件名；启动时 `FileNameIndexBackfill` 在后台给缺这个字段的旧分块按 fileMd5 补写（只补缺的，可重复执行）。关键词查询改为"正文必须匹配 + 文件名、短语作为加分项"，文件名单独成字段、不和正文合并统计；`minimum_should_match`、短语加分都可配置
  - 评测（只跑检索，50 题）：所有组合的最终 Recall@5 都是 76.1%，最终顺序由重排序决定，差异只出现在 BM25 这一路。`minimum_should_match` 30% 让 3 道口语化长问句（para）的相关分块掉出 BM25 前 50，召回阶段 Recall@50 从 100% 降到 97.8%，默认不开；短语加分 1.5 没有可测量的影响，默认不开；文件名权重越大 BM25 排序越差（权重 2 时 BM25 MRR 0.591→0.560），因为本地知识库的文件名不具描述性（paismart.pdf 这类），加权会把同一文件的所有分块一起抬高。默认取 0.5（BM25 MRR 0.587，基本无损）
  - 局限：评测集里没有"按标题找文档"的问题，文件名加分的收益这次没法量化，只验证了不伤现有效果；本地开发库的旧索引里 `fileName` 早先被动态映射成了标准分词（不是 IK），无法原地改分词器，本地评测是在标准分词上做的。生产索引原来没有这个字段，发布后会按模板建成 IK
- [x] **评测补 NDCG 与分阶段指标**（文章 04 题）
  - 现状：`RetrievalMetrics.java:12-20` 只有 Recall@K（命中率口径）、MRR、文档覆盖率；评测集 `eval/paismart-eval-v1.jsonl` 50 题；只评最终结果，召回阶段（RRF 前 50 条）和精排阶段的效果没有分开看
  - 目标：加 NDCG@K，并分别统计召回阶段的 Recall@50 和精排阶段的 NDCG@5，这样调召回窗口和调重排序能分开判断收益；阈值 0.0 和 0.1 的 Recall 和 MRR 完全相同，但逐题对比 0.1 仍在过滤前五名里的低分噪声（两道 RustFS 题 0.1 下返回 2 条，0 下多出 3 条 0.06 到 0.09 分的无关分块），现有指标只看第一个相关结果，反映不出这类差别，分阶段指标之外还要补一个看噪声的指标（如 Precision@K）
  - 完成：`HybridSearchService.searchWithTrace` 额外返回每一路前 50 条、RRF 候选（重排序的输入）和"前 topK 名里被阈值丢掉的条数"。评测每题记录：召回阶段命中、BM25 / KNN 各自的命中和首个相关名次、精排阶段 NDCG@K（二元相关，理想排序取候选池里的相关条数）、噪声指标（Precision@K、不相关条数、返回条数）、RRF 候选明细（带原始分）。报告汇总新增 `stages`，`scripts/eval_compare.py` 一并打印。评测接口新增 `retrievalOnly`：只跑检索、不生成不打分，调检索参数时一轮约 1 分钟，不花大模型 Token
  - Precision 的分母是实际返回条数而不是固定的 K：固定分母下"返回 2 条、2 条相关"和"返回 5 条、2 条相关"都是 0.4，反映不出阈值过滤噪声的效果
  - 基线数字（50 题，topK 5）：召回阶段 Recall@50 100%（BM25 97.8%、KNN 100%），精排 NDCG@5 0.545，最终分块级 Recall@5 76.1%、MRR 0.59。召回已经饱和，短板在重排序把相关分块排进前 5
  - 阈值 0.0 和 0.1 对比：Recall@5、MRR、NDCG 完全相同；0.1 在 2 道 RustFS 题的前 5 名里丢掉 6 条不相关结果，Precision@K 19.1%→20.4%，每题不相关 4.04→3.91 条。无答案题在两种阈值下都返回 5 条（它们的重排序分 0.18 到 0.30，高于 0.1），拒答目前仍靠提示词


## 第四轮：RAG知识库测评集教程（2026-10-13）

来源：toBeBetterJavaer `docs/src/sidebar/itwanger/ai/paismart-rag-eval-set.md`，对照 HEAD `951edd22`。文章 08 节的面试答案按期望方案写，下面是和源码的差距。

- [ ] **评委和生成回答使用不同厂商的模型**（文章 08 节第 04 题追问）
  - 现状：生成回答（`service/eval/RagEvalCaseRunner.java:147`）和评委打分（`:158`）都经过 `completeAllowingOneRetry` 调用 `llmProviderRouter.completeReActTurn`（`:219`），走同一条供应商调用链，评委和被评回答是同一个模型（当前 deepseek-v4-pro）
  - 目标：新增 `eval.judge-provider` 配置，评委固定使用另一家供应商的模型（如 qwen），报告 `config` 同时记录生成模型和评委模型；保留人工抽样复核，交叉评审数据显示两个模型会同向偏宽松（`docs/eval/reports/README.md` 人工校准一节，模型间 kappa 0.87、与人工 0.61 / 0.73）
- [ ] **测评集接入发布流程，指标比基线差就拦截**（文章 08 节第 05 题）
  - 现状：评测只能由管理员手动调用 `POST /api/v1/admin/eval/run`（`controller/RagEvalAdminController.java:58`）触发，前后对比靠人工运行 `scripts/eval_compare.py`，部署脚本 `scripts/deploy-release.sh` 里没有评测环节
  - 目标：发布前自动运行 `paismart-eval-v1.jsonl`，和存档的基线报告按题型对比，分块级 Recall@5、MRR、评委准确率任一项下降超过阈值（如 3 个百分点）或出现失败用例就阻断发布，报告随发布记录归档
- [ ] **检索模式异常时报告直接告警，评测检索不占管理员配额**（文章 03 节、08 节第 05 题）
  - 现状：报告只统计 `retrievalModeCounts`（`service/eval/EvalReportAggregator.java:59`），出现 `TEXT_ONLY` 等降级模式不会告警；评测检索的问题向量化用发起评测的管理员 id 作为请求方（`service/HybridSearchService.java:104`、`:130`），计入该用户的 Embedding 查询限流，额度用完后检索静默降级为纯文本
  - 目标：报告汇总里非 `HYBRID` 模式占比超过阈值时给出 `warning`；评测检索的向量化改用 `system-eval` 请求方并豁免按用户的次数限制（全网 token 预算仍生效），权限过滤仍按发起评测的管理员

