package com.knowledge.ai.core.model;

import lombok.Data;

/**
 * 分页请求参数（T-004）。
 *
 * <p>契约（《10-接口约定汇总》§0）：
 * <b>请求</b> 用 {@code page}（从 1 开始）+ {@code size}（默认 20）；
 * <b>响应</b> 用 {@code { list, total, page, size }}（见 {@link PageResult}）。
 *
 * <p>写法上作为控制器方法参数直接接收即可，Spring 会自动绑定查询串：
 * <pre>
 *   &#64;GetMapping("/users")
 *   public ApiResponse&lt;PageResult&lt;UserRow&gt;&gt; list(PageQuery query, ...) { ... }
 * </pre>
 *
 * <p>越界处理：{@code page < 1} 归一到 1；{@code size < 1} 归一到 20；
 * {@code size} 上限 {@value #MAX_SIZE}，防止一次拉爆数据库。
 */
@Data
public class PageQuery {

    public static final int DEFAULT_PAGE = 1;

    public static final int DEFAULT_SIZE = 20;

    /** 单页上限，防止前端传 size=100000 拖垮库。 */
    public static final int MAX_SIZE = 200;

    private Integer page = DEFAULT_PAGE;

    private Integer size = DEFAULT_SIZE;

    /** 归一化后的页码，恒 {@code >= 1}。 */
    public int normalizedPage() {
        return (page == null || page < 1) ? DEFAULT_PAGE : page;
    }

    /** 归一化后的单页条数，恒 {@code 1..MAX_SIZE}。 */
    public int normalizedSize() {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /** 数据库 offset，与 {@code LIMIT} 配合使用。 */
    public int offset() {
        return (normalizedPage() - 1) * normalizedSize();
    }

    /** 便捷构造：只要单页条数的场景（如「最近 10 条」）。 */
    public static PageQuery of(int page, int size) {
        PageQuery query = new PageQuery();
        query.setPage(page);
        query.setSize(size);
        return query;
    }
}
