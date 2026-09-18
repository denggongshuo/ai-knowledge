package com.knowledge.ai.repository;

import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.entity.SysModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 模型配置仓储（`T-900`~`T-903`）。
 *
 * <p>行数是个位数量级（四类、每类几条），所以这里**不做分页、不做投影**，
 * 查询一律取整行。真正的约束不在性能上，而在**唯一性** ——
 * 「同类型至多一条启用」由库层的 `uk_model_active` 唯一索引保证
 * （应用层并发下会出双启用，见 《09》§1.15）。
 */
public interface SysModelConfigRepository extends JpaRepository<SysModelConfig, Long> {

    /**
     * 全量列表，按「类型 → 排序 → id」稳定排序。
     *
     * <p>排序在 SQL 里做而不是在 Service 里 `sort`：列表的展示顺序是**需求口径**
     * （`sort_order` 就是为它存在的），放在一处比散在组装逻辑里更难漂移。
     */
    List<SysModelConfig> findAllByOrderByModelTypeAscSortOrderAscIdAsc();

    /** 某类型的全部行（含未启用），供列表分区与启用切换时"把同类型其余置为未启用"。 */
    List<SysModelConfig> findByModelTypeOrderBySortOrderAscIdAsc(ModelType modelType);

    /** 某类型**当前启用**的那一条。最多一条（唯一索引保证），但用 {@code List} 取以免并发窗口里抛异常。 */
    List<SysModelConfig> findByModelTypeAndIsActiveTrue(ModelType modelType);
}
