-- S9-M12-B · 图谱旁路检索可观测性（T-C02）
--
-- 旁路字段与主链路 trace 同源：没有单独建表，避免运营侧两个来源漂移。
ALTER TABLE sys_retrieval_trace
    ADD COLUMN graph_used TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '本次图谱旁路是否真正命中有效关系'
        AFTER degrade_note,
    ADD COLUMN graph_millis BIGINT NOT NULL DEFAULT 0
        COMMENT '图谱旁路耗时 ms；未运行为 0'
        AFTER graph_used,
    ADD COLUMN graph_fallback TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '图谱旁路是否超时/异常后静默降级'
        AFTER graph_millis,
    ADD COLUMN graph_entities VARCHAR(512) NULL
        COMMENT '命中的实体名，逗号分隔；空集写 NULL，不写空串'
        AFTER graph_fallback;
