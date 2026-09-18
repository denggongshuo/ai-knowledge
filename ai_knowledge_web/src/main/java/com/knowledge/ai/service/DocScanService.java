package com.knowledge.ai.service;

import com.knowledge.ai.core.async.AsyncTaskService;
import com.knowledge.ai.core.storage.ClamAvScanner;
import com.knowledge.ai.core.storage.DocStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 文档的恶意扫描编排（`T-O04` 步 3）—— **异步**，不阻塞上传响应。
 *
 * <h3>为什么必须异步（`O4` §6 的原话）</h3>
 * <p>「恶意扫描**不应阻塞上传响应**（用户等的是受理确认）。建议：准入闸里做**同步的**
 * 大小/类型/炸弹检查（都是轻量的头部解析），ClamAV 放**异步**（进 `O1` 的 worker 队列），
 * 扫描未过则把文档置为 `FAILED` + 可执行原因」。
 *
 * <p>这一条不是偏好，是量级：扫描一份 100MB 文档要几十秒，而上传接口现在几百毫秒返回。
 * 把它挪进来等于让每个用户上传时干等一个杀毒软件的扫描时间。
 *
 * <h3>命中之后做什么</h3>
 * <ol>
 *   <li>{@code sys_document.scan_status = 'INFECTED'}；</li>
 *   <li>文档置 {@code FAILED} + 可执行的原因（`G4-3`）—— 它不会被解析、更不会被索引；</li>
 *   <li>记一条 **HIGH** 审计（`O4` 判据 4：「`FILE_MALICIOUS` + 审计 `HIGH`」）。</li>
 * </ol>
 * <p>⚠️ **不删 MinIO 里的对象**：那是取证材料。留着它、标记它、不索引它 ——
 * 与"删了就没人知道发生过什么"相比，留痕是安全事件里更该保住的那一半。
 *
 * <h3>{@code FILE_MALICIOUS} 这个错误码在本链路里不抛给用户</h3>
 * <p>因为扫描发生在**响应之后** —— 用户早已拿到"已受理"。它出现在两个地方：
 * 文档的失败原因（列表上那行红字）与审计日志。`ErrorCode` 里仍然保留它，
 * 是因为将来若有同步扫描的入口（比如小于 1MB 的文件），那条路要抛同一个码，
 * 用户看到的话术必须一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocScanService {

    /** 异步任务类型。 */
    public static final String TASK_TYPE = "DOC_SCAN";

    private final DocStorage docStorage;
    private final ClamAvScanner scanner;
    private final DocRecordWriter recordWriter;
    private final DocScanAuditTrail auditTrail;
    private final AsyncTaskService asyncTaskService;

    /**
     * 提交一次扫描（异步）。**在转存成功之后调用** —— 扫描要读的对象那时才存在。
     *
     * @return 扫描任务的 taskId
     */
    public String submit(long kbId, long docId, long ownerId, String objectKey, String fileName) {
        return asyncTaskService.submit(TASK_TYPE,
                // 业务坐标要带上：`#109`/`#130` 那一族教过，"任务看得出来有东西在跑、
                // 却认不出是哪个库哪篇文档"，排查时等于没有
                AsyncTaskService.TaskSubject.of(kbId, docId, ownerId),
                // 幂等键含 docId：同一篇文档的转存只会发生一次，重复提交也只跑一次
                TASK_TYPE + ":" + docId, 0, context -> {
                    context.stage("安全扫描");
                    ClamAvScanner.ScanResult result = scanAndRecord(docId, objectKey, fileName);
                    context.percent(100);
                    context.complete(new java.util.HashMap<>(java.util.Map.of(
                            "docId", docId,
                            "verdict", result.verdict().name(),
                            "detail", result.detail())));
                });
    }

    /**
     * 同步扫描并落库（也是本类的**可测入口** —— 异步体只调它）。
     *
     * <p>把"扫描 + 落库 + 拦下"收成一条同步路径，是为了让判据 4/6 能用真字节直接验，
     * 不必去等一个后台 tick。异步那层只负责"什么时候跑"，不承载任何判据。
     */
    public ClamAvScanner.ScanResult scanAndRecord(long docId, String objectKey, String fileName) {
        byte[] bytes;
        try {
            bytes = docStorage.open(objectKey);
        } catch (RuntimeException ex) {
            // 读不到对象（被清过桶 / 转存其实没成）—— 那不是"扫不了"，是这篇文档本身有问题
            recordWriter.markScan(docId, ClamAvScanner.Verdict.SKIPPED,
                    "安全扫描未执行：文档对象读取失败（" + ex.getMessage() + "）");
            throw ex;
        }

        ClamAvScanner.ScanResult result = scanner.scan(bytes, fileName);
        recordWriter.markScan(docId, result.verdict(), result.detail());

        if (result.verdict() == ClamAvScanner.Verdict.INFECTED) {
            // 这里**不抛**：抛出去只会变成一个 FAILED 的异步任务，而我们需要的是
            // "文档被拦下 + 审计留痕"这两件实实在在的副作用
            recordWriter.markFailed(docId, result.detail()
                    + " —— 该文档已被隔离，不会进入检索；请确认文件来源后重新上传");
            auditTrail.maliciousBlocked(docId);
            log.error("[准入] 文档被安全扫描拦下并置为 FAILED：docId={} file={} 签名={}",
                    docId, fileName, result.detail());
        } else {
            log.info("[准入] 安全扫描结论：docId={} file={} verdict={} detail={}",
                    docId, fileName, result.verdict(), result.detail());
        }
        return result;
    }
}
