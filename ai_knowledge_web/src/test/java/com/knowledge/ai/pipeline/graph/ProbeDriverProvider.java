package com.knowledge.ai.pipeline.graph;

import org.neo4j.driver.Driver;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 记数用的 `ObjectProvider<Driver>` 替身（`S9-M12-B`）。
 *
 * <h3>为什么需要它</h3>
 * <p>判据 1/2 要的是「**零驱动调用**」—— 而这件事只有**数调用次数**才看得见：
 * 真拿一个连不上的 Neo4j，断言照样可能绿（异常被静默降级吃掉了），
 * 那恰好是本批最怕的"绿得没有意义"。
 *
 * <p>⚠️ **本仓 pom 没有 Mockito**（见 `RetrievalTestStubs` 的同一条说明），
 * 所以这里手写一个最小实现：`ObjectProvider` 的抽象方法只有 4 个，
 * 其余（`stream` / `orderedStream` / `getIfAvailable(Supplier)` / …）都是默认方法。
 */
final class ProbeDriverProvider implements ObjectProvider<Driver> {

    private final Driver driver;
    private int calls;

    ProbeDriverProvider(Driver driver) {
        this.driver = driver;
    }

    /** 到现在为止被问过几次"驱动可用吗"（每一次都算一次潜在的真调用）。 */
    int calls() {
        return calls;
    }

    @Override
    public Driver getObject() {
        calls++;
        return driver;
    }

    @Override
    public Driver getObject(Object... args) {
        calls++;
        return driver;
    }

    @Override
    public Driver getIfAvailable() {
        calls++;
        return driver;
    }

    @Override
    public Driver getIfUnique() {
        calls++;
        return driver;
    }
}