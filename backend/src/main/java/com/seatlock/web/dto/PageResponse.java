package com.seatlock.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Our own pagination envelope.
 *
 * <p>Spring's {@code Page} serialises to a large, unstable JSON shape - it
 * includes the whole {@code Pageable}, a {@code Sort} object, and fields whose
 * names have changed between Spring versions. Returning it directly makes the
 * framework's internals part of your public API, and a Spring upgrade then
 * becomes a breaking client change.
 *
 * <p>Five fields is all a client needs.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
    public static <E, D> PageResponse<D> of(Page<E> page, Function<E, D> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }

    public static <T> PageResponse<T> of(List<T> content, Page<?> page) {
        return new PageResponse<>(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isLast());
    }
}
