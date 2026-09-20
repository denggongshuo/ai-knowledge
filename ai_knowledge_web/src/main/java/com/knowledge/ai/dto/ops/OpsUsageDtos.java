package com.knowledge.ai.dto.ops;

import java.util.List;

/**
 * 运营分析 —— 使用统计页（需求 `08` §3）的回话形状（`T-A05`）。
 *
 * <h3>字段名以**页面**为准</h3>
 * <p>这一页的前端（`OpsUsageView.vue` + `types/index.ts`）在 mock 阶段就已经写好了，
 * 字段名与需求 §3.1~§3.5 的表格列一一对应 ⇒ 本批是「**接真**」而不是「新做一页」，
 * 所以 DTO 的组件名**逐字沿用前端既有类型名**（`activeUsers` / `totalQuestions` /
 * `perUser` / `avgTopScore` …），一个都不改名。改名的代价是前端要跟着动，
 * 而那正是「接真」这种批次**不该有**的改动面。
 *
 * <h3>🔴 哪几个数**可以是 {@code null}**，以及为什么不是 0</h3>
 * <p>本页有一批"比率型"字段，它们的**分母可以是 0**（没有活跃用户 / 没有提问 / 没有可比行）。
 * 那几种情形一律回 {@code null}，**不回 0** —— 与 `OpsRetrievalDtos` 的
 * 「四个数**可为 null**」同一条口径：{@code 0} 会被读成"确实是 0"，
 * 而"算不出来"与"结果是 0"在运营页上是**两句相反的话**
 * （"人均提问 0 次"会被读成"大家问了但没深度"，真相是"根本没人问"）。
 *
 * <table>
 *   <tr><th>字段</th><th>分母为 0 时</th></tr>
 *   <tr><td>{@code activeRate}</td><td>{@code totalUsers == 0}</td></tr>
 *   <tr><td>{@code avgPerUser}</td><td>{@code activeUsers == 0}</td></tr>
 *   <tr><td>{@code workdayAvg}/{@code weekendAvg}/{@code dailyAvg}</td><td>窗口内**该类型的天数为 0**</td></tr>
 *   <tr><td>{@code perUser}（部门行）</td><td>该部门 {@code activeUsers == 0}</td></tr>
 *   <tr><td>{@code percent}（部门行 / 库行）</td><td>窗口内 {@code questions == 0}</td></tr>
 *   <tr><td>{@code avgTopScore}（用户行）</td><td>该用户**没有一行有分数**（空召回不进分母）</td></tr>
 * </table>
 *
 * <h3>{@code dept} / {@code kbName} 这两列是**名字**，而分组键是 id</h3>
 * <p>部门名取自 `sys_department`、库名取自 `sys_kb`（**含软删的**：软删的也要有名字，
 * 否则"某一个库"与"其他"的账对不上 —— 与 `OpsRetrievalDtos.KbQualityRow` 同一条处置）。
 * ⚠️ 部门是**改名就会跟着变**的：`V36` 冻结的是**归属**（`dept_id`），不是名字。
 * 需求 §3.7 要防的是「部门**调整**影响历史统计」，而那指的是归属。
 */
public final class OpsUsageDtos {

    private OpsUsageDtos() {
    }

    /**
     * `GET /ops/usage/overview` —— 四个核心指标（需求 §3.1）。
     *
     * @param activeUsers    周期内**至少提问 1 次**且**账号可用**的用户数（`G8-8`）
     * @param totalUsers     总用户数（分母：全部非软删账号，**含**停用/待激活 —— 见 `OpsUsageService` 的口径段）
     * @param activeRate     活跃率（百分数，一位小数）；{@code totalUsers == 0} ⇒ {@code null}
     * @param totalQuestions 周期内问答请求数（= 打点行数，与页 11 / 页 20 **同源**）
     * @param avgPerUser     人均提问（一位小数）；{@code activeUsers == 0} ⇒ {@code null}
     * @param coveredDepts   有活跃用户的部门数
     * @param totalDepts     部门总数（**不含**虚拟的「未分配」节点，见 `OpsUsageService`）
     * @param workdayAvg     工作日日均（一位小数）；窗口内无工作日 ⇒ {@code null}
     * @param weekendAvg     周末日均（一位小数）；窗口内无周末 ⇒ {@code null}
     * @param dailyAvg       日均（一位小数）；窗口内无任何天 ⇒ {@code null}
     */
    public record Overview(long activeUsers,
                           long totalUsers,
                           Double activeRate,
                           long totalQuestions,
                           Double avgPerUser,
                           long coveredDepts,
                           long totalDepts,
                           Double workdayAvg,
                           Double weekendAvg,
                           Double dailyAvg) {
    }

    /**
     * 趋势里的一个点（需求 §3.2）。
     *
     * <p>⚠️ {@code weekend} / {@code today} 是**服务端现算的展示标记**，不落库 ——
     * 与通知中心「相对日期落库当天对、第二天就错」同一条理由。
     *
     * @param date    日历天（`yyyy-MM-dd`）
     * @param label   展示标签（`MM-dd 周X`）—— 与原型一致
     * @param value   当日提问数（**没有提问的那天是 0，不是缺一行**）
     * @param weekend 是否周末（颜色承载信息：周末柱灰、今天柱浅）
     * @param today   是否今天
     */
    public record TrendPoint(String date, String label, long value, boolean weekend, boolean today) {
    }

    /**
     * `GET /ops/usage/trend` —— 时间序列（需求 §3.2）。
     *
     * @param list  恰好 **N 个点**（N = 窗口天数）：窗口内**每一天都在**，没提问的那天补 0。
     *              🔴 这是「7 天合计 = 提问总数」那条验收要点成立的**前提** ——
     *              只回"有数据的那几天"会让折线看着对、合计差一截，而差的那一截没人发现
     * @param total 各点合计（== `overview.totalQuestions`，同一窗口、同一个读侧入口）
     */
    public record Trend(List<TrendPoint> list, long total) {
    }

    /**
     * 部门维度的一行（需求 §3.3）。
     *
     * @param dept        部门名（`dept_id = 0` ⇒ 「未分配」）
     * @param activeUsers 该部门**至少提问 1 次**且账号可用的用户数
     * @param totalUsers  该部门总人数（分母，含停用/待激活）
     * @param questions   该部门提问数
     * @param perUser     人均（一位小数）；{@code activeUsers == 0} ⇒ {@code null}
     * @param percent     占比（百分数，一位小数）；各行之和**恰好 100.0**（最大余数法，见 `OpsUsageService`）
     */
    public record DeptRow(String dept,
                          long activeUsers,
                          long totalUsers,
                          long questions,
                          Double perUser,
                          Double percent) {
    }

    /**
     * `GET /ops/usage/by-dept` —— 部门维度（需求 §3.3）。
     *
     * <p>🔴 三个合计字段与 `overview` 的对应项**逐位相等**（需求 §3.8 的前三条验收要点）。
     * 它们不是"顺便返回"的 —— 是**让那三条等式可以被判据断言**而存在的：
     * 没有它们，判据只能自己把 list 加起来，那测的是判据自己。
     *
     * @param active    活跃用户合计（== `overview.activeUsers`）
     * @param users     部门人数合计（== `overview.totalUsers`）
     * @param questions 部门提问数合计（== `overview.totalQuestions`）
     */
    public record ByDept(List<DeptRow> list, long active, long users, long questions) {
    }

    /**
     * 知识库热度的一行（需求 §3.4）。
     *
     * @param kbName    库名；**只归单库提问**（`kb_count = 1` 且 `kb_ids` 是单个数字）
     * @param questions 该库被提问次数
     * @param percent   占比（百分数，一位小数）
     */
    public record KbRow(String kbName, long questions, Double percent) {
    }

    /**
     * `GET /ops/usage/by-kb` —— 知识库热度（需求 §3.4）。
     *
     * <h4>🔴 「各库合计 = 提问总数」是**第五次**撞到同一个形状</h4>
     * <p>需求 §3.4 写「各知识库被提问次数与占比，合计 = 提问总数」，而
     * **每次提问覆盖全部可见库**（`ChatRequest` 没有 `kbIds`，`ChatStreamService#chat`
     * 硬传 `null`）⇒ `kb_count` 恒 &gt; 1 ⇒ 一次提问**装不进任何一个库**。
     * 拆给每个库会让合计**超过**提问总数；只数单库行又会让合计**少于**它。
     *
     * <p>处置照页 20 `kb-quality` 的判例（也是需求 §3.4 自己那句
     * 「**与 20 页的知识库提问数必须一致——同一个数不要在两个页面各写一遍**」要求的）：
     * **只把单库提问归给库，跨库提问整体入「其他」** ⇒
     * {@code 各库合计 + othersQuestions == 提问总数} **恒等**（比需求的 {@code =} 更可证伪）。
     *
     * <p>⚠️ 与页 20 的差别是**必然**的：那里 `kb_count = 1` 时**一定有**一个具体库；
     * 这里在**当前链路**上单库行恒为 0 ⇒ `list` 会**只剩「其他」一行**。
     * 页面必须**如实显示这个空态**并说明原因，**不许**拿别的数填满它。
     *
     * @param list             单库行（不含「其他」）
     * @param questions        单库行合计
     * @param othersQuestions  跨库提问数（「其他」桶）；`list` 为空时它就等于提问总数
     * @param othersPercent    「其他」桶的占比（一位小数）。⚠️ 它与 `list` 里那些 `percent`
     *                         **一起**由最大余数法分配 ⇒ 页面把它们并排显示时，
     *                         合计**恰好** 100.0；`othersQuestions == 0` 时为 `null`
     */
    public record ByKb(List<KbRow> list, long questions, long othersQuestions, Double othersPercent) {
    }

    /**
     * 活跃用户排行的一行（需求 §3.5）。
     *
     * <p>🔴 **本页唯一含"个人维度"的端点** ⇒ 需求 §3.7 说的
     * 「用户维度统计仅对管理员开放」就落在它头上（权限码 `ops:usage:users:view`，只给超管）。
     * ⚠️ 但它**只展示姓名与部门**，**不展示具体提问内容** —— `G8-9` 那半句的落点。
     * 表里没有 query_text，也没有任何能反推出提问内容的东西。
     *
     * @param name        真实姓名
     * @param dept        部门名（**快照口径**，取该用户窗口内**最后一天**所属的部门）
     * @param questions   提问次数
     * @param avgTopScore 平均 Top1；分母**只含有分数的行**（空召回不进）；
     *                    没有一行有分数 ⇒ {@code null}（不是 0）
     */
    public record UserRow(String name, String dept, long questions, Double avgTopScore) {
    }

    /**
     * `GET /ops/usage/top-users` —— 活跃用户排行（需求 §3.5）。
     *
     * @param list  按提问次数降序取前 {@code limit} 行
     * @param total **去重后的活跃用户总数**（不是 {@code list.size()}）——
     *              它是"榜外还有多少人"的分母，而 `list` 是被截过的那一段
     */
    public record TopUsers(List<UserRow> list, long total) {
    }
}
