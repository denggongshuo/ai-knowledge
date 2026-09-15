package com.knowledge.ai.core.audit;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审计字段落库前脱敏（T-305 · 门 G3-10）。
 *
 * <h3>为什么挂在「落库前」而不是「读侧」</h3>
 * <p>读侧脱敏要给列表 / 详情 / 导出**三个出口**各写一遍，漏一个就是泄露；
 * 而漏的那个通常是最不容易被测到的那条（比如导出）。
 * 落库前只写一遍就同时堵住三个出口，而且**历史数据不受影响** ——
 * 脱敏是纯新增的写入侧行为，老日志原样可查。
 *
 * <h3>这条挡住的是哪一类漏</h3>
 * <p>产出侧已经做过两层避让：{@code @AuditLog.recordParams()} 默认 {@code false}、
 * {@code AuditAspect#summarizeArgs} 只写类型与长度。但它们挡的是
 * 「注解写错 / 参数被序列化」。挡不住的是**业务把密钥写进了文本字段**：
 * <pre>
 *   PUT /kb/{id}/config  body.description = "临时测试，key 用 sk-abcdefghij0123456789"
 * </pre>
 * 描述、对象名、备注、失败原因、变更明细都是自由文本，运营手一滑就进去了。
 * 而审计日志是**长期留存 180 天 + 可导出**的，等于把密钥复制到了一个
 * 访问控制更弱、留存更久的地方。
 *
 * <h3>方向：宁可误伤，不可漏（T-305 假设 6）</h3>
 * <p>若某个知识库描述里**真的**写着 {@code sk-} 加 16 位字符，它会被打成
 * {@code ***}。这是有意的：
 * <ul>
 *   <li>误伤**在页面上看得见**（审计明细显示 {@code ***}），可以补记；</li>
 *   <li>漏掉是**不可逆**的 —— 密钥已进了长期存储与导出文件。</li>
 * </ul>
 * 所以口径取窄而硬的模式串（不猜语义），命中即打码。反过来把范围放宽
 * （比如"长随机串一律打码"）会毁掉审计可用性 —— 审计的价值在于还原现场。
 */
@Slf4j
public final class SensitiveMasker {

    /** 打码后的占位。不含引号 / 反斜杠，避免把 {@code changes_json} 打成非法 JSON。 */
    public static final String MASK = "***";

    /**
     * 命中即打码的模式（窄口径 4 条）。
     *
     * <p>全部带 {@code {16,}} / {@code {12,}} 这类**长度下界**：短串误伤率高
     * （{@code password=1} 这种测试值打码没有意义），且真实密钥不会短。
     */
    private static final Pattern[] PATTERNS = {

            // ① OpenAI 风格密钥：sk- 后 >=16 位。用占有量词避开灾难回溯（长串 + 无匹配时）。
            Pattern.compile("sk-[A-Za-z0-9_\\-]{16,}+"),

            // ② Authorization 头形态：Bearer <token>
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]{12,}+"),

            // ③ k=v 形态的敏感键。键名清单刻意写死 —— 加宽（如把 "key" 也收进来）
            //    会把 "排序 key=created_at" 这类正常审计内容打掉。
            Pattern.compile("(?i)\\b(api[_-]?key|apikey|access[_-]?key|secret|password|passwd|pwd|token)"
                    + "\\s*[:=]\\s*[\"']?([^\\s\"',;&]{6,}+)"),

            // ④ 云厂商 / 平台前缀密钥（AWS Access Key ID、GitHub PAT、Slack token）。
            //    与 ① 互补：这些形态不以 sk- 开头。
            Pattern.compile("(?i)\\b(akia[0-9a-z]{16}|ghp_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9\\-]{10,})"),
    };

    private SensitiveMasker() {
    }

    /**
     * 对一段自由文本脱敏。{@code null} / 空白原样返回。
     *
     * <p>不做 trim / 截断 —— 本类只负责"去掉密钥"，长度约束由实体列宽与
     * 各调用点自己保证（{@code AuditLogService} 里逐列对应）。
     */
    public static String mask(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String result = raw;
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                // ③ 有捕获组（键名），替换时保留它；其余整段打码。
                // 用 while + appendReplacement 而不是 replaceAll，是为了能在
                // 同一串命中多段时逐段留痕 —— 一次请求里可能同时写了两个密钥。
                result = matcher.groupCount() >= 2
                        ? matcher.replaceAll("$1=" + MASK)
                        : matcher.replaceAll(MASK);
            }
        }
        return result;
    }

    /**
     * 该文本是否命中任一敏感模式。
     *
     * <p>供 {@code AuditLogService} 决定要不要留 warn —— 打码本身是静默的，
     * 但"审计里出现了密钥"这件事**本身值得留痕**：它说明某个业务入口
     * 正在把密钥写进自由文本，是个该被修的上游缺陷。
     */
    public static boolean containsSensitive(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(raw).find()) {
                return true;
            }
        }
        return false;
    }
}
