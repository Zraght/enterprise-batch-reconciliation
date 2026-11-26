package com.joseantonio.reconciliation.application.port.in;

import java.util.List;

public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public PageResult {
        content = List.copyOf(content);
    }

    public static <T> PageResult<T> of(List<T> content, PageQuery query, long totalElements) {
        int totalPages = (int) ((totalElements + query.size() - 1) / query.size());
        return new PageResult<>(content, query.page(), query.size(), totalElements, totalPages);
    }
}
