package com.knowledge.ai.core.ai;

import com.knowledge.ai.core.enums.ModelType;

import java.util.List;
import java.util.Optional;

/**
 * 模型配置的来源（T-014）。
 *
 * <p><b>这是一道刻意为 S6 留的接缝。</b>S0 时 {@code sys_model_config} 是空表
 * （配置管理页与 CRUD 在 S6 的 T-900~T-903），所以只能用 {@code application.yml}
 * 里的 {@code app.model.*} 当引导值；S6 接上 DB 后换一个实现即可，
 * {@link ModelClientRegistry} 与所有业务调用方都不用动。
 *
 * <p>实现选择用 {@code @ConditionalOnMissingBean} 排序：DB 实现一旦存在，
 * {@link BootstrapModelConfigSource} 自动让位，不需要改任何注解或配置。
 */
public interface ModelConfigSource {

    /**
     * 某类型下**全部启用**的配置。
     *
     * <p>为什么要返回列表而不是单条：{@code CHAT} 与 {@code VL} 允许配多条
     * （见 {@link ModelType#allowsMultiple()}），问答页可以让用户选。
     * {@code EMBEDDING} 与 {@code RERANK} 是全局单例，实现侧应当只返回至多一条。
     *
     * <p>约定：**返回空列表表示"该类没有可用配置"**，不要返回 {@code null}。
     */
    List<ModelConfigSpec> listEnabled(ModelType type);

    /**
     * 某类型当前生效的那一条。
     *
     * <p>默认取列表首条。DB 实现应当按「是否启用 + 排序」保证首条就是当前启用的那条。
     */
    default Optional<ModelConfigSpec> active(ModelType type) {
        return listEnabled(type).stream().findFirst();
    }

    /**
     * 来源名称，用于日志与健康端点，便于排查"当前用的是引导配置还是 DB 配置"。
     */
    String sourceName();
}
