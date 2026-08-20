/**
 * 通用分页响应 DTO（响应体，泛型）。
 * <p>
 * 用于统一封装各列表接口的分页返回结构，通过静态工厂方法 {@link #from(Page)}
 * 从 Spring Data 的 {@link Page} 转换而来，使用 Java record 表示。
 */
package com.app.campusagent.lostfound.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(
        List<T> content,     // 当前页的数据列表
        int page,            // 当前页码（从 0 开始）
        int size,            // 每页条数
        long totalElements,  // 总记录数
        int totalPages,      // 总页数
        boolean first,       // 是否为第一页
        boolean last) {      // 是否为最后一页

    /**
     * 静态工厂方法：把 Spring Data 的 {@link Page} 转换成统一的 {@link PageResponse}，
     * 避免把 JPA / Spring Data 内部对象直接序列化暴露给前端。
     */
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
