package com.knowledge.ai.core.ai;

import com.knowledge.ai.core.enums.BillingUnit;
import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.entity.SysModelConfig;
import com.knowledge.ai.repository.SysModelConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 把 `app.model.*`（← `.env`）的引导配置**幂等**落成 {@code sys_model_config} 的行。
 *
 * <h3>它为什么必须与 {@link DbModelConfigSource} 同批合入</h3>
 * <p>DB 来源一旦标了 {@code @Primary}，模型口径就换成了 DB。而这张表在 `S6-M09-A` 之前
 * 实测 **0 行** —— 不落种子的话，启动后 {@code activeSpec(CHAT)} 立刻抛
 * {@code MODEL_NOT_CONFIGURED}，**问答 / 检索 / 解析全线不可用**。
 * 只加种子不加来源则是死数据（没人读）。两者是同一次改动的两半。
 *
 * <h3>幂等：只在表空时落</h3>
 * <p>判据是 {@code count() == 0}，不是"按类型查有没有"。后者会在**管理员删掉某一类**
 * 之后的下一次重启把它**复活** —— 那是"管理员删不掉的东西"，比"少落一条"糟得多。
 *
 * <h3>🔴 一处**有意的不对称**：落下的行 `is_active = true` 而 `last_test_ok = false`</h3>
 * <p>需求《09》§1.6 的启用门槛是「**必须先连接测试通过才能启用**」（`G6-3`）。而种子
 * **必须**把它们落成已启用 —— 否则系统起不来（见上）。于是出现这个组合：
 * 「已启用但从未测试过」。
 *
 * <p>这是**刻意的**，不是漏写：
 * <ul>
 *   <li>`G6-3` 约束的是**通过接口启用**这个动作（`T-902`），种子不是那个动作；</li>
 *   <li>把种子落成未启用 = 系统起不来；落成"已测试通过" = **伪造一次测试结论**，
 *       而 `last_test_at` 会是空的 —— 那才是真的自相矛盾；</li>
 *   <li>`last_test_ok = false` 诚实地表示"**没测过**"，配置页上那条模型的「启用」按钮
 *       因此对这四行是禁用的（它们本就是启用态）—— 一旦管理员想切到别条，就得先测。</li>
 * </ul>
 * 这条不对称已登记进 `S6-M09-A` §9。
 *
 * <h3>不落密钥</h3>
 * <p>{@code api_key_cipher} 留 {@code NULL}（= 用环境变量同名配置），见 {@link ModelConfigSpec}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelConfigSeeder implements ApplicationRunner {

    private final SysModelConfigRepository repository;

    private final ModelProperties modelProperties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long existing = repository.count();
        if (existing > 0) {
            // 正常启动路径。用 debug：每次启动都打一行 info 会让日志里"看起来像出事了"
            log.debug("模型配置已有 {} 行，跳过引导种子", existing);
            return;
        }

        List<ModelType> seeded = new java.util.ArrayList<>();
        for (ModelType type : ModelType.values()) {
            ModelProperties.Endpoint endpoint = modelProperties.endpointOf(type);
            // 没配端点就不落这一行：与 `BootstrapModelConfigSource` 同一条口径 ——
            // "没配 key"应当表现为"该类没有可用模型"，而不是留一行必然调用失败的空壳。
            // 配置页上它自然是空的，管理员可以手工补 —— 那时才谈得上密钥从哪来（见 §9）。
            if (endpoint == null || !endpoint.hasCredentials()) {
                continue;
            }
            repository.save(rowOf(type, endpoint));
            seeded.add(type);
        }

        if (seeded.isEmpty()) {
            log.warn("模型配置表为空，且 app.model.* 里没有任何一类配齐了端点 ——"
                    + "问答 / 检索 / 解析都会报「模型未配置」。请检查 .env 的 *_API_KEY / *_BASE_URL");
        } else {
            log.info("模型配置引导种子已落库：{}（api_key_cipher 留空 = 用 .env 同名配置）", seeded);
        }
    }

    private SysModelConfig rowOf(ModelType type, ModelProperties.Endpoint endpoint) {
        LocalDateTime now = LocalDateTime.now();
        SysModelConfig row = new SysModelConfig();
        row.setModelType(type);
        // 展示名先取模型标识本身（`.env` 里没有"给人看的名字"这一项）。
        // 管理员可在配置页改成「DeepSeek-V3（硅基流动）」这类更好认的名字
        row.setDisplayName(endpoint.getModel());
        row.setProtocol("OpenAI 兼容");
        row.setBaseUrl(endpoint.getBaseUrl());
        row.setApiKeyCipher(null);
        row.setModelName(endpoint.getModel());
        if (type == ModelType.EMBEDDING) {
            // 嵌入的维度与度量是**全局硬约束**（Milvus collection 按它建），只读展示
            row.setDimension(1024);
            row.setNorm("L2");
            row.setMetric("COSINE");
        }
        // params / capabilities 留 NULL：读侧按 ModelConfigDefaults 兜底。
        // 刻意不在这里写死一份 —— 那样"默认值"就被冻在了建行那一刻，
        // 后来调整默认值时老行纹丝不动（见 ModelConfigDefaults 的类注释）
        row.setBillingUnit(BillingUnit.PER_TOKEN);
        row.setCurrency("CNY");
        row.setIsActive(true);
        row.setSortOrder(0);
        row.setLastTestAt(null);
        row.setLastTestOk(false);
        row.setActivatedAt(now);
        row.setNote("由启动期引导配置自动落库（app.model.*）");
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }
}
