package com.foxcash.domain.model;

import java.util.List;

/**
 * Resultado paginado genérico (independente de Spring Data), usado pelas
 * portas de consulta do dashboard/admin — evita vazar {@code Page} para o
 * domínio.
 */
public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResult<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResult<>(items, page, size, totalElements, totalPages);
    }
}
