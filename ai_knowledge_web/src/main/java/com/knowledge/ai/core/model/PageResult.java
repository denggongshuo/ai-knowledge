package com.knowledge.ai.core.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 分页响应体（T-004）。
 *
 * <p>契约（《10-接口约定汇总》§0）：{@code { list, total, page, size }}。
 * 字段名与前端 {@code src/types/index.ts} 的 {@code PageResult<T>} **逐字一致**，
 * 不一致会静默丢字段。
 *
 * <p>注意：{@code page} / {@code size} 是**本次请求的归一化回显**，
 * 不是"总页数"。总页数由前端按 {@code Math.ceil(total / size)} 自算。
 *
 * @param <T> 列表元素类型
 */
@Data
public class PageResult<T> {

    private List<T> list = new ArrayList<>();

    /** 满足条件的总记录数（不是本页条数）。 */
    private long total;

    /** 回显当前页码，从 1 开始。 */
    private int page;

    /** 回显当前单页条数。 */
    private int size;

    public PageResult() {
    }

    public PageResult(List<T> list, long total, int page, int size) {
        this.list = list == null ? new ArrayList<>() : list;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        return new PageResult<>(list, total, page, size);
    }

    public static <T> PageResult<T> of(List<T> list, long total, PageQuery query) {
        return new PageResult<>(list, total, query.normalizedPage(), query.normalizedSize());
    }

    /** 空页，用于「查不到任何数据」但请求本身合法的场景。 */
    public static <T> PageResult<T> empty(PageQuery query) {
        return new PageResult<>(new ArrayList<>(), 0L, query.normalizedPage(), query.normalizedSize());
    }

    /** 单页结果（不分页的列表接口也能复用该壳，page 恒为 1）。 */
    public static <T> PageResult<T> single(List<T> list) {
        List<T> safe = list == null ? new ArrayList<>() : list;
        return new PageResult<>(safe, safe.size(), 1, safe.size());
    }

    /**
     * 元素类型转换。让 Service 层返回实体、Controller 层映射成 VO 时无需手工拆装分页壳。
     */
    public <R> PageResult<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = new ArrayList<>(this.list.size());
        for (T item : this.list) {
            mapped.add(mapper.apply(item));
        }
        return new PageResult<>(mapped, this.total, this.page, this.size);
    }
}
