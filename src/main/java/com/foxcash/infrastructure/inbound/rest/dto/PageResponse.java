package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.PageResult;

import java.util.List;
import java.util.function.Function;

/**
 * Envelope paginado de resposta REST (independente do domínio).
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <S, T> PageResponse<T> from(PageResult<S> result, Function<S, T> mapper) {
        return new PageResponse<>(
                result.items().stream().map(mapper).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages());
    }
}
