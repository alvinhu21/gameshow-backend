package com.gameShows.api;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * The envelope for every paged collection.
 *
 * @param page       zero-based.
 * @param totalPages zero when there are no items at all.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
