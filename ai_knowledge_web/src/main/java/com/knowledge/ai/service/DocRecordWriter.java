package com.knowledge.ai.service;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code sys_document} 的**短事务写点**（`T-500` 的落库侧）。
 *
 * <h3>为什么不把这些语句直接写在 {@link DocService} 里</h3>
 * <p>因为**上传的写入发生在两个不同的线程上**：建行在 HTTP 线程（随请求提交），
 * 回写对象名与失败标记在 {@code taskExecutor} 的工作线程（任务体里）。
 * Spring 的 {@code @Transactional} 是**代理**语义 —— 同类内部自调用不生效，
 * 所以"给 DocService 自己加一个方法再自己调"是拿不到事务的。
 *
 * <h3>为什么每个方法都自成一个事务</h3>
 * <ul>
 *   <li>{@link #markStored} 跑在工作线程上，**调用它的时候 HTTP 线程的事务早已提交**
 *       （见 {@code DocService#upload} 的时序说明），所以它必须自己开；
 *       传播用默认的 {@code REQUIRED} 即可 —— 工作线程上本来就没有事务；</li>
 *   <li>{@link #markFailed} 用 {@code REQUIRES_NEW}：它是在**捕获异常之后**调的，
 *       而异常可能正是由"当前事务已被标记回滚"引起的（例如约束冲突）。
 *       若沿用同一个事务，这条失败标记会被一起回滚掉 —— 于是文档永远停在
 *       {@code UPLOADED}、`error_msg` 恒空，`G4-3`「失败给出具体原因」直接落空。
 *       {@code REQUIRES_NEW} 与 {@code AuditLogService#record}（不变量 I-12）是同一个取舍。</li>
 * </ul>
 *
 * <p>本类**只做写入**，不含任何业务判断 —— 判断留在 {@link DocService}，
 * 这样"为什么这么写"与"怎么写"不会混在两处。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocRecordWriter {

    private final EntityManager entityManager;

    /**
     * 建行并返回新 id。此时 {@code storage_path} 为 {@code null}、状态为 {@code UPLOADED}。
     *
     * <p>取 id 用「按 (kb_id, owner_id, md5) 倒序取第一条」而不是 {@code getGeneratedKeys}：
     * Hibernate 的 {@code createNativeQuery(...).executeUpdate()} **不返回自增键**
     * （返回的是受影响行数）。用同一事务内可见性保证拿到刚插的那条 ——
     * 这也是本方法必须是独立的 {@code @Transactional}` 方法的原因之一。
     */
    @Transactional
    public long insert(long kbId, long ownerId, String title, String fileName, String type,
                       long sizeBytes, String md5) {
        LocalDateTime now = LocalDateTime.now();
        entityManager.createNativeQuery("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count,
                                                  table_total, table_parsed, image_total, image_parsed,
                                                  created_at, updated_at)
                        values (:kbId, :ownerId, :title, :fileName, :type, :size, :md5,
                                null, 'UPLOADED', 0, 0, 0, 0, 0, :now, :now)
                        """)
                .setParameter("kbId", kbId)
                .setParameter("ownerId", ownerId)
                .setParameter("title", title)
                .setParameter("fileName", fileName)
                .setParameter("type", type)
                .setParameter("size", sizeBytes)
                .setParameter("md5", md5)
                .setParameter("now", now)
                .executeUpdate();

        @SuppressWarnings("unchecked")
        List<Number> ids = entityManager.createNativeQuery(
                        "select id from sys_document where kb_id = :kbId and owner_id = :ownerId and md5 = :md5 "
                                + "order by id desc limit 1")
                .setParameter("kbId", kbId)
                .setParameter("ownerId", ownerId)
                .setParameter("md5", md5)
                .getResultList();
        if (ids.isEmpty()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "文档记录写入失败，请重试");
        }
        return ids.getFirst().longValue();
    }

    /** 转存成功后回写对象名。工作线程调用（`T-500` 的任务体第 3 步）。 */
    @Transactional
    public void markStored(long docId, String objectKey) {
        entityManager.createNativeQuery(
                        "update sys_document set storage_path = :path, updated_at = :now where id = :id")
                .setParameter("path", objectKey)
                .setParameter("now", LocalDateTime.now())
                .setParameter("id", docId)
                .executeUpdate();
    }

    /**
     * 标记失败并留下**具体原因**。
     *
     * <p>{@code G4-3} 的判据是「失败态给出**具体原因**（非"失败"两字）」，
     * 所以这里的 {@code reason} 由调用方给出可执行的话（如"文件存储失败：连接超时"），
     * 不是异常类名。超长截断到列宽 512。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long docId, String reason) {
        String msg = reason == null || reason.isBlank() ? "处理失败，请重试" : reason.trim();
        if (msg.length() > 500) {
            msg = msg.substring(0, 500);
        }
        try {
            entityManager.createNativeQuery(
                            "update sys_document set status = 'FAILED', error_msg = :msg, updated_at = :now "
                                    + "where id = :id")
                    .setParameter("msg", msg)
                    .setParameter("now", LocalDateTime.now())
                    .setParameter("id", docId)
                    .executeUpdate();
        } catch (Exception ex) {
            // 连"记下失败"都失败了 —— 只能留日志，不能让异常再往上冒把任务体的原始失败原因盖掉
            log.error("回写文档失败状态时出错：docId={} 原始原因={} err={}", docId, msg, ex.getMessage());
        }
    }
}
