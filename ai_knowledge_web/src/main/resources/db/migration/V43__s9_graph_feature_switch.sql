-- ===========================================================================
-- S9 · 阶段 C：图谱旁路开关（`T-C03` · FR-GRAPH-01）
--
-- 一行种子数据，没有 DDL。它是 `PUT /system/feature/graph` 的事实源 ——
-- 在那之前图谱开关只有 `m05.graph.retrieve.enabled` 这个**启动期**属性，
-- 改它必须重启，而这正是本批要修掉的东西（I-16 要求"关得掉"，可回滚）。
--
-- ⚠️ 与 `m05.graph.retrieve.enabled` 的分工，一句话：
--   本键（sys_setting）= 产品开关，运行时可翻、进审计；
--   那个属性 = 仅在**缺行**时兜底，是部署侧的安全网。
-- 兜底只在缺行时生效，且会留 warn —— 静默兜底比不兜底更糟。
--
-- ⚠️ 新增本键**必须同时**改 `SystemSettings`（常量）与 `SystemSettingService.KEYS`
-- （查询范围）。只改一处的话现象是"配置改了但没人读"—— 与 `V20` 那次同形状
-- （`retrieval.rerank-top-k` 被 KEYS 查了、却没有任何调用方读它）。
--
-- ⚠️ 同样**不要**用 `||` 拼字符串：MySQL 默认 sql_mode 下 `||` 是逻辑或，
-- 会报 `22001 Truncated incorrect DOUBLE value`（见 `V20` 的注释）。
--
-- 值的取舍：默认 `true` = 保持现状。本批把"关得掉"当作能力交付，
-- **不顺手改行为** —— 图谱质量要 `G9-4` 在真嵌入模型上量过才好定。
--
-- 可逆：`DELETE FROM sys_setting WHERE setting_key = 'retrieval.graph-bypass-enabled';`
-- 无业务数据需要回填（该键不驱动任何页面上写死的展示值）。
-- ===========================================================================
INSERT INTO sys_setting (setting_key, setting_value, value_type, group_name, label, description, editable, updated_at)
VALUES ('retrieval.graph-bypass-enabled', 'true', 'BOOLEAN', '检索', '图谱旁路开关',
        '关闭后检索链路完整回到未接图谱时的状态：不建并行支路、不取 Neo4j 驱动、图谱不占融合位（I-16）。仅管检索侧的「旁路」，不影响建图（那是 m05.graph.enabled）',
        1, NOW());
