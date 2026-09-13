package com.knowledge.ai.core.ai;

import com.knowledge.ai.core.enums.ModelType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 {@code application.yml} 的 {@code app.model.*} 读模型配置（T-014 · S0 的引导实现）。
 *
 * <p><b>版本为什么是固定的 {@code v1}</b>：引导配置来自进程启动时读入的 yml / 环境变量，
 * 运行期不会变；改了就必须重启，而重启后缓存本来就是空的。给它编一个假的"版本号"
 * 只会让人误以为支持热更新。DB 实现（S6）才会带真实版本。
 *
 * <p><b>S6 接 DB 时怎么切换</b>：新增的 DB 实现标 {@code @Primary} 即可，本类不用动。
 * 这里**刻意不用 {@code @ConditionalOnMissingBean}** —— 该注解只适用于自动配置类或
 * {@code @Bean} 方法，放在被组件扫描的 {@code @Component} 上会被提前求值而把 Bean
 * 整个跳过（本项目已实测踩到：启动报 {@code No qualifying bean of type 'ModelConfigSource'}）。
 * {@code @Primary} 是显式且可预测的，出问题时一眼能看出用的是哪个来源。
 *
 * <p>四类都返回「至多一条」，因为 yml 里每类只有一组端点。注意这**不等于**产品口径：
 * {@code CHAT} / {@code VL} 允许多条，只是引导配置只提供一条默认值。
 */
@Component
@RequiredArgsConstructor
public class BootstrapModelConfigSource implements ModelConfigSource {

    /** 引导配置的固定版本号。改 yml 需重启，重启后缓存自然清空，故不设动态版本。 */
    private static final String BOOTSTRAP_VERSION = "v1";

    private final ModelProperties modelProperties;

    @Override
    public List<ModelConfigSpec> listEnabled(ModelType type) {
        ModelProperties.Endpoint endpoint = switch (type) {
            case CHAT -> modelProperties.getChat();
            case EMBEDDING -> modelProperties.getEmbedding();
            case RERANK -> modelProperties.getRerank();
            case VL -> modelProperties.getVl();
        };

        // 端点不全就不给出这条配置：让"没配 key"表现为"该类无可用模型"，
        // 由调用方给出明确的业务错误，而不是构造一个必然调用失败的客户端。
        if (endpoint == null || !endpoint.hasCredentials()) {
            return List.of();
        }

        List<ModelConfigSpec> specs = new ArrayList<>(1);
        specs.add(new ModelConfigSpec(
                "bootstrap-" + type.name().toLowerCase(),
                type,
                endpoint.getBaseUrl(),
                endpoint.getApiKey(),
                endpoint.getModel(),
                BOOTSTRAP_VERSION,
                true));
        return specs;
    }

    @Override
    public String sourceName() {
        return "bootstrap(app.model.*)";
    }
}
