# AI Knowledge · 企业知识库系统

> **个人项目**：从零实现的文档知识库系统，覆盖 **文档入库 → 解析/清洗 → 向量化 → 混合检索 → 重排 → 引用溯源** 的完整链路，
> 并配套 **权限与密级、模型管理、审计、检索质量评测与运维分析**。
>
> 后端 `Spring Boot 4.0` / `Java 25` / `Spring AI 2.0.1` · 前端 `Vue 3 + TypeScript` · 约 **12 万行** · **150** 个测试类

---

## ⚠️ What this is / What this isn't

**这是一个真实、可运行、带自测的个人工程 —— 但它不是生产系统。请先读完这一节。**

**是**

- 一个把 RAG 全链路 + 权限体系 + 运维分析**完整实现并有测试覆盖**的个人项目（v1 已完成）
- 附**可复现的检索评测**：24 题评测集（单跳/多跳/聚合）、金标 `Recall@5`、**负向锚**（查 `A-100` 不得召回 `A-1000`）、`nprobe` 三档量化
- 一份**真实的踩坑与取舍记录**：表格识别、密级贯通、引用角标、幂等入库、超时与降级的决策过程都写在 `docs/` 里

**不是**

- ❌ **不是生产系统**：单人开发，已知缺陷存在；未做压测，未做安全渗透
- ❌ **没有外部用户**：全部数据为**合成 / 演示数据**，不涉及任何真实业务或个人信息
- ❌ **未在公网验证过**：请勿直接用于生产环境
- ❌ 不是"框架"或"平台"，是一个具体的业务系统实现

> ⚠️ **默认凭据警告**：仓库的种子数据带有演示账号（如超管 `admin`）与固定短信验证码。
> **它们仅用于本地演示库**，禁止用于任何生产或公网部署。部署前必须更换全部默认凭据与 `JWT_SECRET`。

---

## 功能

| 模块        | 内容                                                                                      |
| --------- | --------------------------------------------------------------------------------------- |
| **认证与账号** | 注册 / 登录 / 找回密码、JWT、短信验证码（开发期固定码）、登录失败锁定、登录日志                                            |
| **权限与审计** | 角色 / 权限点（RBAC）、**数据密级贯通**、数据权限范围、知识库级授权、**append-only 审计链**（traceId 串起 响应体 → 日志 → 审计表） |
| **知识库与文档** | 知识库管理、文档上传（分片 / 秒传 / 断点续传）、解析与清洗、**表格识别**、**病毒扫描**、**入库防护**、版本管理、回收站           |
| **检索与问答** | **混合检索 + RRF 融合**、意图路由、**HyQ 假设问题补全**、**知识图谱旁路**（带开关）、重排、**SSE 流式问答**、**引用角标与原文定位** |
| **运营分析**  | 检索质量分析、**未命中闭环**、使用统计、离线预计算、审计导出                                                        |
| **系统配置**  | **模型管理**（多模型 / 多能力注册）、通知中心、参数配置                                                          |

---

## 架构

```
                     ┌─────────────────── 前端 Vue 3 + TS ───────────────────┐
                     │   知识库 / 文档 / 问答 / 权限 / 模型 / 运维 运营台        │
                     └───────────────────────┬──────────────────────────────┘
                                             │  REST + SSE
┌────────────────────────────────────────────▼─────────────────────────────────────────────┐
│  接入层   TraceFilter(traceId)  →  Spring Security / JWT  →  RBAC + 数据密级 + DataScope   │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  ★ 文档入库链路  pipeline                                                                    │
│    上传 → 入库防护/病毒扫描 → 路由(routing) → 视觉理解(vision) → 清洗(cleaning)                  │
│         → 分块(chunking) → 向量化(vectorize) → 索引增强(enhance/HyQ)                          │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  ★ 检索问答链路  pipeline                                                                     │
│    查询 → 混合检索(向量 + 关键词) → RRF 融合 → [知识图谱旁路 ✓开关] → 重排 → 上下文组装              │
│         → SSE 流式生成 → 引用解析(CitationParser，角标 ↔ 原文定位)                              │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│  治理横切  审计（append-only + 敏感字段脱敏）· 检索链路追踪 · 未命中闭环 · 模型用量记录 · 健康自检        │
└──────────────────────────────────────────────────────────────────────────────────────────┘
     存储：MySQL(元数据/审计) · Milvus(向量) · MinIO(对象) · Neo4j(图谱) · Valkey(缓存/限流)
```

### 检索链路里值得一提的几处

| 位置                                       | 做了什么                                                              |
| ---------------------------------------- | ----------------------------------------------------------------- |
| `pipeline/retrieve/HybridRetriever`      | 向量 + 关键词混合召回                                                       |
| `pipeline/retrieve/RrfFusion`            | **RRF（倒数排名融合）** 合并多路结果，避免手调权重                                       |
| `pipeline/enhance/HyqGate`               | **HyQ 假设问题生成**，带 gate 控制（不是所有查询都值得多花一次模型调用）                          |
| `pipeline/graph/GraphRetriever`          | 知识图谱多跳旁路，**由 `GraphFeatureToggle` 控制**——实测无区分度后已降级关闭（保留了开关，见 `docs/`） |
| `pipeline/retrieve/MissSink`             | **未命中闭环**：把检索不到的问题落库，供运营分析定位知识空洞                                     |
| `pipeline/retrieve/RetrievalTraceWriter` | 检索链路全量打点，可复盘"这一问为什么召回了这些片段"                                         |
| `pipeline/chat/CitationParser`           | 引用角标解析，支持"点角标跳原文"                                                   |

> **关于图谱旁路**：它是"加了又被关掉"的功能。做分题型 A/B 验收后结论是**无区分度**，于是用开关降级关闭而不是删代码。
> 相关的验收结论与对比图保留在 `docs/` 中——**保留"为什么砍掉它"的过程，比保留功能本身更有价值。**

---

## 技术栈

| 层   | 选型                                                                                                            |
| --- | ------------------------------------------------------------------------------------------------------------- |
| 后端  | Spring Boot **4.0.0** · Java **25** · Spring AI **2.0.1** · Spring MVC（命令式）+ WebClient（调模型）· JPA · Flyway |
| 前端  | Vue 3 · TypeScript · Vite · Element Plus · Pinia · Vue Router                                                      |
| 存储  | MySQL 8.4（元数据/审计）· Milvus 2.6（向量，dim 1024 / `bge-m3`）· MinIO（对象）· Neo4j 5.26（图谱）· Valkey 8.1（缓存/限流）      |
| 安全  | Spring Security · JWT · ClamAV（病毒扫描）· 敏感字段脱敏                                                                    |
| 可观测 | traceId 全链路（响应体 / 日志 / 审计表）· Actuator 自定义健康组件 · logback 异步不丢日志                                                 |
| 部署  | Docker Compose（9 个服务）                                                                                          |

> **为什么用 MVC 而不是全响应式**：项目整体是命令式技术栈（JPA、`@Transactional`、`ThreadLocal` 登录上下文、Servlet 过滤器贯穿 traceId、分片续传），
> 只在此之上用 WebClient 非阻塞地调模型接口。详见 `ai_knowledge_web/pom.xml` 里的注释与 `docs/需求文档/11-非功能需求与技术架构.md`。

---

## Quickstart

**前置**：JDK 25 · Maven 3.9+ · Node 22+ · Docker Desktop（Compose v2+）

```bash
git clone https://gitee.com/shuogehaoshuai/kb_rag.git ai-knowledge
cd ai-knowledge

# ── ① 起基础设施（首次 Milvus 需要 1~2 分钟才 healthy，属正常）──────────
docker compose up -d
docker compose ps                # 期望 mysql / minio / etcd / milvus / neo4j / valkey 均 healthy

# ── ② 配置环境变量（只需一次）──────────────────────────────
cd ai_knowledge_web
cp .env.example .env
#   必填：JWT_SECRET（openssl rand -base64 48）+ MYSQL_* / NEO4J_* / MINIO_* / REDIS_*
#   可选：各类模型 API Key —— 【留空也能启动】，只是不能问答
#   ⚠️ .env 已被 gitignore，永不入库

# ── ③ 起后端（★ 工作目录必须是 ai_knowledge_web，.env 按工作目录加载）──
mvn spring-boot:run

# ── ④ 自检：五个存储必须全部连通 ─────────────────────────────
curl http://localhost:8080/api/health/storage
# {"code":0,"message":"success","data":{"allUp":true,"upCount":5,"total":5,...},"traceId":"..."}

# ── ⑤ 起前端 ─────────────────────────────────────────────
cd ../ai_knowledge_app
npm install
npm run dev                      # http://localhost:5173
```

**不需要手工建表**：Flyway 在应用启动时执行 `db/migration/V*.sql`（`baseline-on-migrate: true`），
并在空库上自动建表 + 灌种子、建 MinIO bucket、建 Milvus collection。

**常见坑（都写进排查表了）**：见 `docs/后端开发文档/01-本地启动与配置.md` §6 ——
例如看到 `Failed to parse the host:port pair '${MYSQL_HOST}:${MYSQL_PORT}'`，
**病根是 `.env` 没被加载（工作目录不对），不要去查数据库**。

---

## 检索质量评测（这个项目最该看的部分）

检索类项目最容易只讲"我用了什么技术"，讲不出"效果如何"。所以这里有一份**可复现的评测**：

```bash
cd ai_knowledge_web
mvn -B test -Peval -Dtest=RetrievalEvalTest
# 产物：target/eval-report.txt（逐题）· target/eval-nprobe.txt（nprobe 三档）
```

| 项         | 做法                                                                       |
| --------- | ------------------------------------------------------------------------ |
| 评测集       | **24 题**：单跳 / 多跳 / 聚合 **各 8 条**（`src/test/resources/eval/queries.json`） |
| 金标        | 有标准答案片段，可算 **`Recall@5`**                                                 |
| **负向锚**   | 题目要求"查 `A-100` **不得**召回 `A-1000`"（`mustNotRecall`）——防止"看起来召回率高"的假象        |
| 可复现性      | 连跑两次结果一致                                                                 |
| 参数扫描      | 量化 `nprobe` 三档对召回的影响                                                     |
| **不进默认跑批** | 标注 `excludedGroups=eval`，`mvn test` 仍是"全替身、无网络"；评测要显式 `-Peval` 才跑       |

> **语料为什么是合成的、为什么不走 upload → parse**：详见 `docs/后端开发文档/13-S5-检索评测基线.md`。
> 简言之——评测的对象是**检索层**，而合成语料才能构造受控实验（如 `A-100` vs `A-1000` 这类干扰项）。

---

## 项目结构

```
ai-knowledge/
├── ai_knowledge_web/          后端（Spring Boot）
│   ├── src/main/java/com/knowledge/ai/
│   │   ├── core/              横切：security / audit / storage / trace / ops / text / async
│   │   ├── pipeline/          ★ RAG 链路：routing / vision / cleaning / enhance / vectorize
│   │   │                              / retrieve / graph / chat
│   │   ├── controller/ service/ repository/ entity/ dto/
│   ├── src/main/resources/db/migration/   Flyway 迁移（建表 + 种子）
│   └── src/test/java/          150 个测试类（含 eval/ 评测）
├── ai_knowledge_app/          前端（Vue 3 + TS + Vite）
├── docs/
│   ├── 需求文档/              14 篇（认证 / 权限审计 / 知识库文档 / 检索问答 / 运营 / 非功能与架构 …）
│   ├── 后端开发文档/          24 篇（启动配置 / 各 Sprint 结论 / 检索评测基线 / 门禁取证 …）
│   ├── 数据库/ · 原型图/       22 个页面原型 · 数据字典
│   └── 进度/                 业务模块拆分 / 任务清单 / Sprint 排期
├── docker-compose.yml         编排唯一事实源（9 服务）
└── scripts/
```

> `docs/` 里的**开工计划 → 验收结论 → 门禁取证**这套留痕方式，是刻意保留的：
> 判断依据要能落到证据（哪一次提交、哪一条测试、哪一次实验）上，而不是"我记得跑通了"。

---

## 已知限制

- v1 完成，存在已知缺陷，不保证所有边界场景正确
- 检索评测集只有 24 题、语料为合成数据 —— 能验证"链路正确与可复现"，**不等于**生产语料下的效果指标
- 未做压力测试、未做安全渗透；限流与熔断为本地实现
- 前端部分页面共用 mock 层，便于脱离后端独立调试
- 图谱旁路默认关闭（A/B 无区分度）

## License

尚未指定。如需使用请先联系作者。
