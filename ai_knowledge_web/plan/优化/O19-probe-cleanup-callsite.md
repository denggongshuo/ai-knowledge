# O19 · 探针清理的「调用方错位」——**会造行的脚本不在清理路径的调用方里**

> 状态：🟡 已登记（2026-09-20，`S8-M10-C` 认领）· 预估 ≈2.5 人天 · 优先级 **P0**（它是 `S8-M10-B` 那次「全量跑了三次才绿」的根因家族）
> 来源：`S8-M10-B` §9-② 与 `S8-M10-A` §9 的清理账

---

## 1. 由来（一句话）

`S8-M10-B` 收尾时全量跑批**第三次才 0 红**，第三次红在一个**谁都不认领**的残留上：`QaMissFlowTest#overviewComesFromTraces` 断言"7 天打点窗口为空"，而**本批 E2E 留下了一行打点**。当时的诊断是「E2E 与 Java 套件各按自己的水位线清 ⇒ **水位线以下的历史行两不管**」，并已在 `e2e-ops-replay-flow` 用"精确 `session_id`"堵住。

🔴 **本批开工核实，那个诊断只对了一半** —— 更锋利的是这个形状：

| 事实 | 证据 |
|---|---|
| `teardownUserProbe` **确实**含 `sys_model_usage` 的清理 | `probe-teardown.mjs:229`（`via: 'date'`，`delete from sys_model_usage where occurred_at = curdate()`）+ `:692` 的分支 |
| `teardownUserProbe` **确实**含 `sys_qa_miss` 的清理 | `probe-teardown.mjs:218`（`via: 'subquery'`，按探针用户 id 删） |
| 但它的**调用方只有两个脚本** | `e2e-audit-backfill.mjs`（3 处：:267/:522/:546）· `e2e-auth-flow.mjs`（:483） |
| 🔴 **唯一真造 `sys_model_usage` 行的脚本不在调用方里** | `e2e-chat-flow.mjs` —— 它**读** `sum(calls) from sys_model_usage` 做断言（:660），却**不调** `teardownUserProbe`（文件头 :30 明写"本脚本自己按会话 id 清"） |
| 🔴 **它同时也造 `sys_qa_miss` 行且从不清** | 它真提问 ⇒ `ChatStreamService#finish` 打点 ⇒ `miss_flag=1` 时 `MissSink` 往池子写行；而池子的清理只在 `teardownUserProbe` 里 |
| 另两条仍是**纯水位线** | `e2e-ops-miss-flow`（:217 记 `max(id)`）· `e2e-chat-flow`（:268 同）· `e2e-ops-replay-flow`（:242 水位线 + **:257 精确 id**，已修半边） |

⇒ **「清理函数存在、调用方存在，但"会造这些行的那个脚本"不在调用方里」** —— 这是「清理必须走必走路径」（`汇总.md` §5）的**第四种形态**。
前三种：① `step()` 一抛就不执行（`#186`）；② `await` 的返回值被丢掉（`#187`）；③ `process.exit()` 早退（本仓自己的验证脚本）。

## 2. 影响（今天就在漏）

- `e2e-chat-flow` 每跑一轮 ⇒ 当天的 `sys_model_usage` 行留下（等某次 `mvn test` 的 `teardownUserProbe` 才收走，若那轮没跑到就永久留着）；
- 同时留下 `sys_qa_miss` 行 —— 而**池子不是探针产物**（`速览` §1 的「跑批前六问」⑥：`sys_qa_miss` 是唯一一张"非探针产物"的表，核残留时不能要求它为 0）⇒ 它的残留**最容易被当成真实数据**；
- 水位线清理对"某一轮没跑到位"的行**永久无主**（`S8-M10-B` 已实证一次）。

## 3. 做法

| 步 | 做什么 |
|---|---|
| 1 | **把清理接进"会造行的脚本"的 `finally`** —— `e2e-chat-flow` / `e2e-ops-miss-flow` / `e2e-ops-replay-flow` 的收尾都调 `teardownUserProbe`（或它拆出的、只清用户族的那个函数）。⚠️ 必须 `finally`，不能是普通 `step()`（`#186`） |
| 2 | **水位线 → 精确 id**（抄 `e2e-ops-replay-flow:257` 的写法）：问答类脚本记下本轮**精确的 `session_id` 列表**，按它删；水位线**只作兜底** |
| 3 | **残留自检加独立观测点** —— 脚本跑完在干净基线上断言 `sys_model_usage where occurred_at = curdate()` 为 **0**、`sys_qa_miss where last_user_id in (探针id)` 为 **0**。⚠️ 不能用"清理函数的返回值"当观测点（`#136`/`#137` 的判例） |
| 4 | **Java 侧对齐**：确认 `ApiTestSupport` 收尾同样覆盖这两张表（`sys_qa_miss` 在 `S8-M10-A` 后新增，当时是否接进 Java 侧兜底**未核**） |

## 4. 判据

| # | 判据 | 方法 | 期望 |
|---|---|---|---|
| 1 | `e2e-chat-flow` 跑完后当天的 `sys_model_usage` 为 0 | 跑脚本 → SQL | 0（跑之前先记基线，不要求全表 0） |
| 2 | `e2e-chat-flow` 跑完后探针账号名下的 `sys_qa_miss` 为 0 | 同上 | 0 |
| 3 | 清理走**必走路径** | 让脚本在倒数第二步抛错 → 看残留 | 仍然清干净（**证伪方式：把清理挪出 `finally` ⇒ 转红**） |
| 4 | 水位线兜底仍在（双重保险） | 手工插一行"水位线以下"的会话，跑脚本 | 那一行**仍不被删**（证明不是"一律删"，射程说清） |
| 5 | Java 侧收尾同样覆盖 | `mvn test -Dtest=QaMissFlowTest+ModelUsageFlowTest` 前后对账 | 无净增 |

## 5. 明确不做

- **不做**"按前缀一律删"的宽射程清理（会误删真实提问沉淀的池子行 —— `sys_qa_miss` 的登记项 `why` 已写明这一条）。
- **不做**把 `sys_model_usage` 改成"带归属列"（那是另一件事：它无归属列是**事实**，`ProbeArtifacts.NO_OWNERSHIP_COLUMN` 就是为它建的台账）。
- **不假设**水位线清理是错的 —— 它是**兜底**，本方案只是把它降级成第二道，第一道换成精确 id。
