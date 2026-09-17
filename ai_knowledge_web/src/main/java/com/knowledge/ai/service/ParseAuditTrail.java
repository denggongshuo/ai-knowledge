package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import org.springframework.stereotype.Component;

/**
 * 解析链路的**审计留痕点**（`T-O06` · `优化/O6` 步 1 与步 3）。
 *
 * <h3>为什么单独一个类，而不是把 {@code @AuditLog} 挂在既有方法上</h3>
 * <p>三条记录发生在**两个不同的线程**，而 {@code @AuditLog} 是**方法级**注解、
 * 一个方法只能挂一条：
 * <ul>
 *   <li>{@link #parseSubmitted} —— <b>请求线程</b>（`DocService#upload` 里调），
 *       此刻有 {@code LoginUserContext}，所以操作人是**上传者本人**；</li>
 *   <li>{@link #parseDone} / {@link #parseFailed} —— <b>解析 worker 线程</b>
 *       （`ParseWorker#execute` 里调），此刻没有登录态也没有请求，
 *       切面据此写成 {@code system} / 「系统」（见 {@code AuditAspect#build}）。</li>
 * </ul>
 * 而 {@code DocService#upload} 自己已经挂着「上传文档」那条了 —— 一个方法挂不了第二条，
 * 所以"触发解析"这件事的留痕必须另有一个承载点。这**不是**为了凑一个类：
 * 三条记录本来就是三件独立的事（谁触发的 / 跑成了 / 跑砸了）。
 *
 * <h3>为什么"触发解析"打在**请求线程**，而不是真的投递作业的那一刻</h3>
 * <p>作业是在**转存完成之后**才入队的（`S4-E1` §3.4 假设 1：源文件要等 MinIO 落盘，
 * 否则重启后重跑解析拿不到源），而那已经在异步体里 —— 那一刻登录态**已经不在了**。
 *
 * <p>而 `O6` §2 的核心立场正是：「把**权限决策点前移到提交时**（那时有登录态），
 * 让工作线程只执行**已经被授权过的**既定作业」，「这比让 {@code LoginContext} 跨线程干净得多」。
 * 所以：**决策与留痕在请求线程，执行在 worker** —— 两者用同一个 traceId 串起来。
 *
 * <h3>方法体是空的，这不是漏写</h3>
 * <p>审计由 {@code AuditAspect} 在方法返回后落库，注解本身就是全部内容。
 * 空方法体的唯一职责是**让切面有一个连接点**。加任何业务逻辑都会让"审计"与"业务"
 * 重新纠缠 —— 那正是把这些点从 {@code DocService} 里拆出来的原因。
 *
 * <h3>新增这三条时的三处同步（`汇总.md` §5 的硬口径）</h3>
 * <ol>
 *   <li>本类的 {@code @AuditLog}；</li>
 *   <li>{@code AuditRiskPolicy.IMPLEMENTED} 逐条 {@code register(...)} **带理由**；</li>
 *   <li>{@code AuditCoverageFlowTest#expectedActions()} 的冻结清单。</li>
 * </ol>
 * 漏任一处都会红 —— ①②由契约用例双向断言，③防的是"扫描式断言对漏挂注解是盲的"。
 */
@Component
public class ParseAuditTrail {

    /**
     * 「**谁在什么时候触发了哪篇文档的解析**」—— 企业审计必问的那一条（`O6` §2 痛点 1）。
     *
     * <p>{@code target} 用 {@code '文档#' + #docId} 而不是沿用上传那条的 {@code 知识库#N}：
     * `O6` §2 痛点 2 点名的就是「**粒度到库、不到文档** —— 出问题时无法回答
     * 『这个人动过哪些文档』」。两条记录（上传 / 触发解析）都由库级细化到文档级，
     * 于是按 {@code target = 文档#N} 一筛就是这篇的完整生命周期（`O6` 判据 6）。
     *
     * <p>风险等级 {@code NORMAL}：触发解析**不扩大任何权限、不删除任何数据**，
     * 它只是让一篇已经授权可见的文档变得可检索。标成 HIGH 会让高危列表被日常上传淹没
     * （《05》§4.3 那句「界限必须写死，否则标记会失去意义」）。
     *
     * @param kbId  所属知识库（写进 target 便于按库追溯）
     * @param docId 文档 id —— **target 的粒度就是它**
     */
    @AuditLog(module = AuditModule.DOC, action = "触发解析", risk = RiskLevel.NORMAL,
            target = "'文档#' + #docId")
    public void parseSubmitted(long kbId, long docId) {
        // 体为空是有意的：注解即全部内容，切面在方法返回后落库。见类注释。
    }

    /**
     * 解析**成功**（`O6` 步 3）。由 {@code ParseWorker} 在 worker 线程调用 → 操作人显示为「系统」。
     *
     * <p>{@code target} 与 {@link #parseSubmitted} **同一个表达式** ——
     * 这是"提交 → 结果"能在审计页串成一条的前提之一（另一半是 traceId，见 `ParseWorker`)。
     */
    @AuditLog(module = AuditModule.DOC, action = "解析完成", risk = RiskLevel.NORMAL,
            target = "'文档#' + #docId")
    public void parseDone(long docId) {
        // 见类注释：空体是刻意的。
    }

    /**
     * 解析**失败**（`O6` 步 3）。
     *
     * <p>为什么成功与失败是**两个动作名**而不是一个动作 + 结果字段：
     * 审计页的筛选器里 `action` 是独立的筛选项（"解析失败"是运维最常筛的那一条），
     * 而"结果"列看的是接口级 SUCCESS/FAILED —— 那是**接口调用**成没成，
     * 与"解析这件事成没成"不是一回事（接口早就 200 返回了，解析是几分钟后的事）。
     *
     * <p>⚠️ 失败原因要经切面进 {@code fail_reason} 吗？**不** ——
     * `@AuditLog` 的 `failReason` 记的是**方法抛异常**的原因，而这里的"失败"是
     * **正常返回的业务结果**（`parseDocument` 从不抛异常，它返回一个 FAILED 的 Map）。
     * 所以原因由 {@code ParseWorker} 写进日志与 {@code sys_async_task.error_msg}，
     * 审计这条只回答"这次解析是失败的"这一事实。**两处职责不重叠**。
     */
    @AuditLog(module = AuditModule.DOC, action = "解析失败", risk = RiskLevel.NORMAL,
            target = "'文档#' + #docId")
    public void parseFailed(long docId) {
        // 见类注释：空体是刻意的。
    }
}