package com.knowledge.ai.service;

import com.knowledge.ai.core.ai.ModelConfigSpec;
import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.core.trace.TraceContext;
import com.knowledge.ai.entity.SysModelUsage;
import com.knowledge.ai.repository.SysModelUsageRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 模型用量的**唯一写入口**（`T-905`）。
 *
 * <h3>为什么必须收成一个类，而不是各调用点自己 save</h3>
 * <p>需求 `09` §2.9 对通知中心写了一条通用纪律：「**通知生成点统一**（如异步任务完成回调处
 * 集中产出），避免散落在各业务代码里」。用量与通知是同一个形状的横切关注点 ——
 * 它有**五个采集点**（问答生成 / 文档摘要 / HyQ 假设问句 / 向量化 / 重排，加上本批新增的
 * VL 描述共六个）。散写的话，下一个采集点要么漏掉、要么把口径写成第六种。
 * 收成一个类之后，"还能往哪儿记用量"这个问题只有一个答案。
 *
 * <h3>失败只 warn，不抛</h3>
 * <p>用量是**观测数据**：一次 INSERT 失败不该把一篇能解析的文档判失败、更不该把
 * 一次已经生成完的问答变成错误（用户在界面上看到的是"这次没答上来"，而其实答完了）。
 * 与 {@code AsyncTaskService.TaskContext#persistProgress} 是同一条取舍。
 * ⚠️ 但**不静默** —— 每次失败都留 warn 日志：「静默降级视为缺陷」是《02》的验收口径第一条。
 *
 * <h3>为什么用 {@code REQUIRES_NEW}</h3>
 * <p>采集点大多在**别人的事务里**（解析任务的落库事务、问答的收尾事务）。
 * 用默认的 {@code REQUIRED} 的话，用量写入会被绑进那个事务 ——
 * 主业务回滚时用量跟着消失（可接受），但**主业务因为别的原因回滚时，
 * "这次调用真的发生过"这条事实也一起没了**（不可接受：钱已经花了）。
 * 开新事务让用量独立提交，代价是"主业务回滚了但用量记着"——
 * 这个方向的误差是**保守的**（多记一次调用），比少记一次安全。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelUsageRecorder {

    private final SysModelUsageRepository repository;

    /**
     * 用途桶 —— 与需求 `09` §1.13 的 `byPurpose` 四个桶 + VL 一一对应。
     *
     * <p>{@link #label} 是**给页面直接渲染的中文名**，取值与 mock
     * （{@code mock/index.ts:1416-1421} 的 `byPurpose[].name`）逐字一致 ——
     * 同 {@code ChatDtos.TraceStage#name} 的口径：**改文案就等于改前端显示**。
     *
     * <p>⚠️ {@link #SUMMARY} 的中文名是「文档级摘要 + 内容标签」而不是「文档摘要」：
     * mock 里那个桶同时装了摘要与内容标签两类调用，本批按 mock 的粒度对齐
     * （后端这两步确实都走 `IndexEnhancer`）。改名会让页面上的桶名与
     * 已冻结的前端展示漂移。
     */
    @Getter
    public enum Purpose {

        /** 问答生成（`ChatStreamService`）。 */
        CHAT("问答生成"),

        /** 文档级摘要 + 内容标签（`IndexEnhancer`）。 */
        SUMMARY("文档级摘要 + 内容标签"),

        /** HyQ 假设性问题生成（`IndexEnhancer`）。 */
        HYQ("HyQ 假设性问题生成"),

        /** 文档向量化（`ChunkVectorizer`）。 */
        EMBEDDING("文档向量化（嵌入）"),

        /** 重排（检索链路）。 */
        RERANK("检索重排"),

        /** VL 图片描述（`T-907`）。 */
        VL_DESC("图片理解（VL 描述）"),
        ;

        private final String label;

        Purpose(String label) {
            this.label = label;
        }
    }

    /**
     * 记一次（或一批）调用。
     *
     * @param spec      产出这次调用的模型规格。取自**调用点当刻**的
     *                  {@code ModelClientRegistry#activeSpec} —— 不要缓存成字段：
     *                  换成别的模型之后，新调用必须记在新模型名下
     * @param purpose   用途桶
     * @param calls     调用次数（一次问答 = 1，一次批量向量化 = 文本条数）
     * @param promptTokens     输入 token；**没回报时传 0**（见 {@code SysModelUsage} 类注释）
     * @param completionTokens 输出 token；**只有总数、没拆分时整笔记进 promptTokens**
     *                         （宁可当输入计，也不均分 —— 均分会造出一个两边都不对的数）
     * @param latencyMs 耗时（毫秒）；**没测时传 {@code null}**，不要传 0
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ModelConfigSpec spec, Purpose purpose, int calls,
                       long promptTokens, long completionTokens, Integer latencyMs) {
        if (spec == null) {
            return;
        }
        record(spec.type(), spec.id(), spec.model(), purpose, calls,
                promptTokens, completionTokens, latencyMs);
    }

    /**
     * 按 (类型, 标识, 模型名) 记 —— 给**拿不到 {@link ModelConfigSpec} 的调用点**用。
     *
     * <p>为什么需要这个重载：{@code T-907} 的 VL 描述器**按设计不持有注册中心**
     * （`T-014` 的纪律：任何模块要调模型都经注册中心，而描述器那一层只能看见
     * {@code VisionCaller}）。它手上只有 {@code VisionModelRef(id, name)}，
     * 没有 spec。为了记一行用量去把注册中心塞进描述器，等于为了统计破坏那条结构纪律 ——
     * 那是本末倒置。
     *
     * @param modelId 模型配置 id 的**字符串形式**（可空 / 可非数字，非数字时落 NULL）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ModelType type, String modelId, String modelName, Purpose purpose,
                       int calls, long promptTokens, long completionTokens, Integer latencyMs) {
        if (type == null || purpose == null || calls <= 0) {
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            String name = modelName == null || modelName.isBlank() ? "<未知模型>" : modelName;

            SysModelUsage row = repository
                    .findBucket(today, type.name(), name, purpose.name())
                    .orElseGet(() -> {
                        SysModelUsage fresh = new SysModelUsage();
                        fresh.setOccurredAt(today);
                        fresh.setModelType(type.name());
                        fresh.setModelId(numericId(modelId));
                        fresh.setModelName(name);
                        fresh.setPurpose(purpose.name());
                        fresh.setCalls(0);
                        fresh.setPromptTokens(0L);
                        fresh.setCompletionTokens(0L);
                        return fresh;
                    });

            row.setCalls(row.getCalls() + calls);
            row.setPromptTokens(row.getPromptTokens() + Math.max(promptTokens, 0L));
            row.setCompletionTokens(row.getCompletionTokens() + Math.max(completionTokens, 0L));
            row.setLatencyMs(mergeLatency(row.getLatencyMs(), latencyMs));
            // 只留最后一次：本表的能力边界写在 V27 文件头注，不假装能逐次归因
            row.setTraceId(TraceContext.current());
            repository.save(row);
        } catch (RuntimeException ex) {
            log.warn("用量记录失败（不影响业务）：type={} purpose={} model={} 原因={}",
                    type, purpose, modelName, ex.getMessage());
        }
    }

    /** 便捷重载：只记次数，不记 token 与耗时。 */
    public void recordCalls(ModelConfigSpec spec, Purpose purpose, int calls) {
        record(spec, purpose, calls, 0L, 0L, null);
    }

    /**
     * 耗时合并：两个都为 {@code null} 时保持 {@code null}（**没测**），
     * 否则相加 —— 其中一个是 {@code null} 时按另一个计。
     *
     * <p>为什么不做成 {@code a + b}：那样 {@code null} 会静默变成 0，
     * 于是"这次没测"与"这次 0ms"在下一次累加时被永远压平（`Summary` 的 javadoc
     * 记着同一类教训：单值模型会把混合档位拉平）。
     */
    private static Integer mergeLatency(Integer existing, Integer incoming) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null) {
            return existing;
        }
        return existing + incoming;
    }

    /**
     * 规格 id → 数字主键。
     *
     * <p>DB 生效时 {@code ModelConfigSpec#id()} 是表主键的字符串形式；
     * 引导配置路径上是 {@code bootstrap-chat} 这种非数字串 —— 那时**如实返回 null**，
     * 而不是编一个数字（与 `#138` / `#161` 的 {@code numericId} 同一条口径：
     * 前后端模型标识对不上时**如实不发**）。
     */
    private static Long numericId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(id.strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
