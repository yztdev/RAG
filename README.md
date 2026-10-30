<<<<<<< HEAD
派聪明（PaiSmart）是一个企业级的 AI 知识库管理系统，采用检索增强生成（RAG）技术，提供智能文档处理和检索能力。

核心技术栈包括 ElasticSearch、Kafka、WebSocket、Spring Security、Docker、MySQL 和 Redis。

它的目标是帮助企业和个人更高效地管理和利用知识库中的信息，支持多租户架构，允许用户通过自然语言查询知识库，并获得基于自身文档的 AI 生成响应。

![派聪明多模块架构](https://cdn.tobebetterjavaer.com/stutymore/README-20250730102133.png)

系统允许用户：

- 上传和管理各种类型的文档
- 自动处理和索引文档内容
- 使用自然语言查询知识库
- 接收基于自身文档的 AI 生成响应

用到的技术栈包括，先说后端的：

+ 框架 : Spring Boot 3.4.2 (Java 17)
+ 数据库 : MySQL 8.0
+ ORM : Spring Data JPA
+ 缓存 : Redis
+ 搜索引擎 : Elasticsearch 8.10.0
+ 消息队列 : Apache Kafka
+ 文件存储 : MinIO
+ 文档解析 : Apache Tika
+ 安全认证 : Spring Security + JWT
+ AI集成 : DeepSeek API/本地 Ollama+豆包 Embedding
+ 实时通信 : WebSocket
+ 依赖管理 : Maven
+ 响应式编程 : WebFlux

后端的整体项目结构：

```bash
src/main/java/com/yizhaoqi/smartpai/
├── SmartPaiApplication.java      # 主应用程序入口
├── client/                       # 外部API客户端
├── config/                       # 配置类
├── consumer/                     # Kafka消费者
├── controller/                   # REST API端点
├── entity/                       # 数据实体
├── exception/                    # 自定义异常
├── handler/                      # WebSocket处理器
├── model/                        # 领域模型
├── repository/                   # 数据访问层
├── service/                      # 业务逻辑
└── utils/                        # 工具类
```

再说前端的，包括：

+ 框架 : Vue 3 + TypeScript
+ 构建工具 : Vite
+ UI组件 : Naive UI
+ 状态管理 : Pinia
+ 路由 : Vue Router
+ 样式 : UnoCSS + SCSS
+ 图标 : Iconify
+ 包管理 : pnpm

前端的整体项目结构：

```bash
frontend/
├── packages/           # 可重用模块
├── public/             # 静态资源
├── src/                # 主应用程序代码
│   ├── assets/         # SVG图标，图片
│   ├── components/     # Vue组件
│   ├── layouts/        # 页面布局
│   ├── router/         # 路由配置
│   ├── service/        # API集成
│   ├── store/          # 状态管理
│   ├── views/          # 页面组件
│   └── ...            # 其他工具和配置
└── ...               # 构建配置文件
```

## 核心功能

### 知识库管理

派聪明提供了完整的文档上传与解析功能，支持文件分片上传和断点续传，并支持标签进行组织管理。文档可以是公开的，也可以是私有的，并且可以与特定的组织标签关联，以便更好地进行权限分类。

![派聪明文档处理](https://cdn.tobebetterjavaer.com/stutymore/README-20250730102808.png)

### AI驱动的RAG实现

派聪明的核心是 RAG 实现：

![派聪明聊天交互](https://cdn.tobebetterjavaer.com/stutymore/README-20250730102837.png)

- 将上传的文档进行语义分块
- 调用豆包 Embedding 模型为每个文本块生成高维向量
- 将向量存储到 ElasticSearch 以支持语义搜索和关键词搜索
- 可以根据用户的查询检索相关文档
- 为 LLM 提供完整的上下文，从而生成更准确、基于文档的响应内容

### 上下文检索与父子检索

分块切小了，检索更精确，但一块文本离开原文可能读不懂，比如只写着“营收比上季度增长 3%”，没说是哪家公司、哪个季度。派聪明在入库和问答两头各补一步。

**上下文检索（入库时，默认关闭）**：参考 Anthropic 2024 年 9 月公开的 Contextual Retrieval。向量化之前，让 LLM 给每个分块写一两句上下文说明，交代它出自哪份文档、属于哪个部分。

- 文档可能比模型上下文长得多，所以只给有界窗口：文档标题 + 本批分块前后各 `neighbour-chunks` 个相邻分块，总长度不超过 `max-window-chars`。
- 每 `chunks-per-request` 个相邻分块合并成一次 LLM 调用，同一段窗口只发送一次；最多 `concurrency` 个调用同时在途，调用方为 `system-contextual`，受全局 LLM Token 预算约束，触发限流时按配置退避重试。system 提示词固定、user 消息以文档标题开头，DeepSeek、通义千问等接口的自动前缀缓存可以命中。
- 说明写入 `document_vectors.chunk_context`。embedding 用“说明 + 空行 + 原文”；ES 里 `textContent` 仍存原文，说明单独存 `chunkContext` 字段，BM25 用 `combined_fields` 把两个字段当成一个字段打分；rerank 也看“说明 + 原文”。展示、引用、证据片段只用原文。
- 某一批调用失败或模型漏掉某个分块，只记日志，这些分块按原文入索引，不影响入库。已有说明的分块不会重复生成。
- embedding 模型重建（`/api/v1/admin/knowledge-index/rebuild`）复用 MySQL 里已生成的说明，不重新调用 LLM；单文件重建索引会重新解析、重新生成说明。给已有知识库补说明，对文件逐个重建索引即可。
- 启动时会给已有的线上索引追加 `chunkContext` 字段（ES 允许给已有索引加新字段，不需要重建），切换别名或回滚到旧索引前也会先补齐。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `knowledge.contextual-retrieval.enabled` | `false` | 总开关（`KNOWLEDGE_CONTEXTUAL_RETRIEVAL_ENABLED`） |
| `knowledge.contextual-retrieval.chunks-per-request` | `8` | 一次 LLM 调用处理的相邻分块数 |
| `knowledge.contextual-retrieval.neighbour-chunks` | `2` | 窗口在本批分块前后各带几个相邻分块 |
| `knowledge.contextual-retrieval.max-window-chars` | `6000` | 送给 LLM 的窗口最大字符数 |
| `knowledge.contextual-retrieval.max-context-chars` | `200` | 单个分块说明的最大字符数 |
| `knowledge.contextual-retrieval.concurrency` | `2` | 同时在途的 LLM 调用数（所有文件共享） |
| `knowledge.contextual-retrieval.rate-limit-retries` / `rate-limit-backoff-seconds` | `2` / `10` | 触发全局 LLM 预算限流时的重试 |

**父子检索（问答时，默认开启）**：检索仍然在小分块上做，命中以后把分块连同同一文件里前后相邻的分块（父块）交给模型。

- 父块从命中分块开始向后、向前交替扩展，最多各 `neighbour-chunks` 个，单个父块不超过 `max-chars-per-hit`，相邻分块之间的切分重叠会去掉。
- 多个命中落在同一个父块里时，父块只在第一次出现时给出，后面的编号只附命中分块并注明“上下文已在 [n] 中给出”；所有父块的总长度受 `max-total-chars` 约束，用完后剩余命中只给小分块。
- 引用编号、页码、锚点和证据片段仍然指向命中的小分块。开启引用校验时，按模型实际看到的父块判断引用是否被支撑。
- `search_knowledge`、`generate_summary` 两个工具和离线评测的生成、评委环节用同一套父块，检索指标仍按小分块计算。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `search.parent-context.enabled` | `true` | 总开关（`SEARCH_PARENT_CONTEXT_ENABLED`） |
| `search.parent-context.neighbour-chunks` | `1` | 命中分块前后各最多扩展几个相邻分块 |
| `search.parent-context.max-chars-per-hit` | `1500` | 单个父块最大字符数 |
| `search.parent-context.max-total-chars` | `6000` | 一次检索所有父块的总字符数上限 |

### 企业级多租户

派聪明通过组织标签支持多租户架构。每个用户可以创建或加入一个或多个组织，每个组织可以拥有独立的知识库和文档管理。这样，企业可以在同一系统中管理多个团队或部门的知识库，而无需担心数据混淆或权限问题。

![派聪明的安全架构](https://cdn.tobebetterjavaer.com/stutymore/README-20250730103118.png)

### 实时通信

系统采用 WebSocket 技术，提供用户与 AI 系统之间的实时交互，支持响应式聊天界面，便于知识检索和 AI 互动。

### 文档冲突检测

完全相同的文件在上传时按 MD5 去重。内容冲突（两篇文档对同一个配置项给了不同的值）在文件向量化完成后异步检测，检测失败只记日志，不影响入库。

- 取新文件前 `max-chunks-per-file` 个分块，逐块在 `knowledge_base` 别名上做 KNN，只在同一可见范围内找其他文件的分块（同一上传者、公开文档、同组织标签，复用检索的权限过滤）。
- 向量余弦相似度达到 `similarity-threshold`，归一化（全半角、大小写、空白、标点）后文本不完全相同，把数字、版本号、日期替换成占位符后文本仍高度相似，且两侧抽出的关键值集合不同，才记为疑似冲突。
- 可选开启 LLM 二次确认，所有候选合并成一次调用，调用失败时保留规则结果。
- 疑似冲突写入 `document_conflicts` 表，同一分块对已有 PENDING 或 IGNORED 记录时不会重复写入。系统不会自动删除或覆盖任何文档，由管理员决定保留哪一篇。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `knowledge.conflict.enabled` | `true` | 总开关（`KNOWLEDGE_CONFLICT_ENABLED`） |
| `knowledge.conflict.similarity-threshold` | `0.9` | 分块向量余弦相似度阈值 |
| `knowledge.conflict.masked-text-similarity-threshold` | `0.8` | 屏蔽关键值后的文本相似度阈值 |
| `knowledge.conflict.max-chunks-per-file` | `200` | 每个新文件最多检查的分块数 |
| `knowledge.conflict.neighbors-per-chunk` | `3` | 每个分块取几个最相似的其他文件分块 |
| `knowledge.conflict.max-conflicts-per-file` | `50` | 单个文件一次最多记录的疑似冲突数 |
| `knowledge.conflict.llm-confirm.enabled` | `false` | LLM 二次确认（`KNOWLEDGE_CONFLICT_LLM_CONFIRM_ENABLED`） |

管理员接口（`/api/v1/admin/**` 仅 ADMIN 可访问）：

- `GET /api/v1/admin/document-conflicts?status=PENDING&page=1&size=20` 分页查看疑似冲突
- `POST /api/v1/admin/document-conflicts/{id}/resolve`，请求体 `{"action": "KEEP_NEW", "note": "..."}`。`KEEP_NEW` 通过现有删除流程删掉旧文档，`KEEP_EXISTING` 删掉新文档，`IGNORED` 只标记不是冲突。被删除文件参与的其他待处理冲突会标记为 `OBSOLETE`。

### 离线 RAG 评测

评测把检索和生成分开看，回答错了能分清是没搜到，还是搜到了没用对。

- 数据集为 JSONL，每行一个用例：`{"question": "...", "expectedFileMd5s": ["..."], "expectedFileNames": ["..."], "expectedEvidence": ["..."], "referenceAnswer": "..."}`，期望文档 MD5 和文件名至少填一个才计算检索指标。`expectedEvidence` 可选，填答案分块里一定会出现的短语，命中分块包含任意一个才算相关（分块级）；不填时同一文件的任何分块都算命中（文件级），一份大文档里召回了不相干的分块也算对，Recall 会虚高。匹配前对分块和短语都做 NFKC 归一化并去掉空白，PDF 抽出的康熙部首（如 `⼤`）也能匹配。用短语而不是分块编号，是为了调整分块策略后标注依然有效。
- 内置两份数据集：`sample-eval.jsonl` 是 5 条示例；`paismart-eval-v1.jsonl` 是 50 条正式测评集，覆盖单点事实、同义改写、多跳综合、资料冲突、私有文档、跨文档、无答案七类题型（id 前缀即题型），全部带分块级标注。`scripts/eval_compare.py` 按题型拆分报告，并把多份报告并排对比。
- 检索以发起评测的管理员身份调用线上同一条混合检索链路，计算 Recall@K（前 K 条里出现任一相关分块即命中）、文件级 Recall（`docRecallAtK`，用来和分块级对比）和 MRR，并记录检索模式（`HYBRID` / `HYBRID_RRF` / `TEXT_ONLY`）。检索的问题向量化计入该管理员的 Embedding 查询限流（`embedding-query-request`），每日上限调得太低时检索会降级为 `TEXT_ONLY`，看报告的检索模式统计就能发现。
- 生成沿用问答的提示词约定（`ai.prompt.rules` + 检索片段块），用非流式调用得到回答。
- 另起一次 LLM 调用做评委，对照参考答案和检索证据输出 `{"score": 1-5, "correct": true/false, "reason": "..."}`。评委提示词把所有字段当作数据，不因回答更长而加分；输出解析失败的用例，以及检索没召回答案分块、评委却判对的用例（`MISS_BUT_CORRECT`，人工校准发现评委在这类用例上偏宽松）一律标记人工复核，另按 `eval.human-review-sample-rate` 随机抽样人工复核。
- 推理模型的思考过程也计入 `max_tokens`，预算用完时回答为空；生成和评委输出为空时都会重试一次，因长度截断（`finish_reason=length`）的把预算翻倍。报告的 `config.llm` 记录本次用的主模型。
- 报告给出 Recall@K、MRR、评委平均分、准确率和问题归因：`PROMPT_OR_MODEL`（检索命中但回答错，查提示词或模型），`RETRIEVAL_MISS`（检索未命中，查分块和检索），以及逐条明细。报告以 JSON 写入 `eval.output-dir`，文件名带 `label`，调整分块前后各跑一次、用不同 label 区分即可对比。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `eval.output-dir` | `eval-reports` | 报告输出目录（`EVAL_OUTPUT_DIR`） |
| `eval.dataset-dir` | `eval-datasets` | `datasetPath` 只能指向该目录下的文件，或 `classpath:eval/xxx.jsonl` |
| `eval.human-review-sample-rate` | `0.1` | 随机抽样人工复核比例 |
| `eval.random-seed` | 空 | 固定后两次评测抽到同一批用例 |
| `eval.default-top-k` / `eval.max-top-k` | `5` / `20` | 检索条数 |
| `eval.max-cases` | `500` | 单次评测最多用例数 |
| `eval.generation-max-tokens` / `eval.judge-max-tokens` | `4000` / `2000` | 生成和评委的最大输出 tokens；推理模型的思考过程也计入，给小了会只思考不输出 |
| `eval.max-retry-tokens` | `8000` | 输出为空时重试一次，因长度截断的把预算翻倍，但不超过该值 |
| `eval.generation-timeout-seconds` / `eval.judge-timeout-seconds` | `90` / `60` | 生成和评委的超时 |
| `eval.requester-id` | `system-eval` | 生成和评委调用的请求方，以 system 开头不占用户配额 |
| `eval.human-agreement.min-agreement-rate` | `0.8` | 评委与人工一致率低于该值时告警（`EVAL_HUMAN_AGREEMENT_MIN_RATE`） |
| `eval.human-agreement.min-kappa` | 空 | Cohen's kappa 下限，为空时不检查 |
| `eval.human-agreement.min-comparable-cases` | `5` | 可比较用例少于该数量时只提示样本不足 |

管理员接口：

- `POST /api/v1/admin/eval/run`（JSON）：`{"datasetPath": "classpath:eval/sample-eval.jsonl", "topK": 5, "label": "chunk-512"}`，也可以用 `jsonl` 字段直接传内容
- `POST /api/v1/admin/eval/run`（multipart）：`file` 为 JSONL 文件，可带 `topK`、`label` 参数
- `GET /api/v1/admin/eval/runs`、`GET /api/v1/admin/eval/runs/latest`、`GET /api/v1/admin/eval/runs/{runId}` 查看进度、汇总指标和报告路径
- `GET /api/v1/admin/eval/runs/{runId}/report` 获取完整报告 JSON，录入过人工判定时附带 `humanReview`
- `POST /api/v1/admin/eval/runs/{runId}/human-reviews`：录入人工判定，`{"reviews": [{"caseId": "c1", "correct": true, "score": 4, "note": "..."}]}`，`score`（1-5）和 `note` 可选，同一用例重复提交以最后一次为准
- `GET /api/v1/admin/eval/runs/{runId}/human-reviews` 查看人工判定和评委与人工一致率

同一时间只运行一个评测任务，任务状态保存在内存里（最近 20 个），报告文件保存在磁盘上。

**评委校准**：评委模型有自己的偏差，要定期抽一批让人工也判一遍。人工判定保存在报告旁边的 `{报告文件名}.human-reviews.json` 里，报告文件本身不改，重启后按 runId 在 `eval.output-dir` 里找回。只比较评委和人工都给出 `correct` 的用例，计算一致率和 Cohen's kappa（扣掉碰巧一致的部分，正确率很高或很低时一致率会虚高）；人工也打了分的用例额外给出评委分与人工分的平均绝对差。结果挂在任务状态的 `humanAgreement` 上：一致率低于 `min-agreement-rate`（或 kappa 低于 `min-kappa`）时 `status` 为 `NEEDS_RECALIBRATION` 并带 `warning`，说明评分标准要改或者评委模型已经不可靠，本次准确率和平均分不能直接采信，`disagreementCaseIds` 列出分歧用例。
=======
# RAG-
>>>>>>> 8d366527282ff4fa6af69ca40b1a8beffa98b34a
