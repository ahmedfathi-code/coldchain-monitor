package com.coldchain.api.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The JSON shape of every paginated list in the API. A DTO of our own (instead of returning
 * Spring's Page) keeps the response format stable and independent of library changes.
 *
 * @param content       the items of this page
 * @param page          page number, starting at 0
 * @param size          requested page size
 * @param totalElements number of items across all pages
 * @param totalPages    number of pages
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    /** Converts a Spring Data page of entities into a page of DTOs. */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> toDto) {
        return new PageResponse<>(page.getContent().stream().map(toDto).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /** Builds a page from a list that was already limited by the database (LIMIT / OFFSET). */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }
}
