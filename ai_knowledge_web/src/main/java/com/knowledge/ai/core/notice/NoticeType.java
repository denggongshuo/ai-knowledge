package com.knowledge.ai.core.notice;

/**
 * 通知类型 —— 需求《09-需求-系统配置与通用.md》§2.2 的**四类**（`T-B00`）。
 *
 * <h3>为什么"能不能关"是枚举的自带属性，而不是散在 Service 里的 if</h3>
 * <p>需求 §2.5 的表格里，「系统告警」一行写的是「❌ **强制开启**」—— 它是一个**类型属性**，
 * 不是某次请求的校验。把它做成 {@link #canDisable()} 之后：
 * <ul>
 *   <li>{@code PUT /notices/settings} 只需问一次枚举，不会再写成"如果 type 等于 ALERT 就…"；</li>
 *   <li>将来加第五类时，**编译器会逼着写新类型的这一栏**（枚举构造器少一个参数编不过）。</li>
 * </ul>
 * 与 {@code ModelType#allowsMultiple} 是同一种处置（那边也踩过"两处各写一遍、两边都不对"的坑，
 * 见 `S6-M09-A` 的 `#167`）。
 *
 * <h3>词表对齐</h3>
 * <p>取值与前端 {@code types/index.ts} 的 {@code NoticeType} **逐字一致**
 * （`UPLOAD` / `ALERT` / `PERMISSION` / `SYSTEM`）—— 它是接口契约，不是内部枚举，
 * 改名要连着 `10-接口约定汇总` §11 一起改。
 */
public enum NoticeType {

    /** 批量上传完成、解析失败、表格未识别。 */
    UPLOAD("上传与解析", true),

    /** 索引失败、存储超限、任务中断。**强制开启**（需求 §2.5）。 */
    ALERT("系统告警", false),

    /** 角色调整、知识库授权变化。 */
    PERMISSION("权限变更", true),

    /** 模型配置变更、重建完成、版本更新。 */
    SYSTEM("系统消息", true);

    private final String label;

    /** 用户能不能在设置里关掉它。{@code false} = 强制开启。 */
    private final boolean canDisable;

    NoticeType(String label, boolean canDisable) {
        this.label = label;
        this.canDisable = canDisable;
    }

    /** 分类导航上显示的中文名（需求 §2.3 的左侧分类）。 */
    public String label() {
        return label;
    }

    /**
     * 该类通知能否被用户关闭。
     *
     * <p>⚠️ {@code ALERT} 恒为 {@code false}：需求 §2.5 的「强制开启」是**硬约束**，
     * 落库侧同样卡住（`sys_notice_setting` 不允许存在 `ALERT + enabled=0` 的行）。
     */
    public boolean canDisable() {
        return canDisable;
    }

    /** 设置页的说明文案（与前端 mock 的 `hint` 同口径）。 */
    public String hint() {
        return switch (this) {
            case UPLOAD -> "批量上传完成、解析失败、表格未识别";
            case ALERT -> "索引失败、存储超限、任务中断（强制开启，不可关闭）";
            case PERMISSION -> "角色调整、知识库授权变化（强烈建议保留）";
            case SYSTEM -> "模型配置变更、重建完成、版本更新";
        };
    }

    /**
     * 宽松解析：认不出返回 {@code null}。
     *
     * <p>返回 null 而不是抛 —— 调用方（查询条件的 `type` 参数）要的语义是
     * "这个筛选条件我不认识，当没传"，而不是 500。
     */
    public static NoticeType parseOrNull(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw)) {
            return null;
        }
        for (NoticeType type : values()) {
            if (type.name().equalsIgnoreCase(raw.trim())) {
                return type;
            }
        }
        return null;
    }
}
