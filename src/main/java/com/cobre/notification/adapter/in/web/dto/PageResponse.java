package com.cobre.notification.adapter.in.web.dto;

import com.cobre.notification.application.port.in.PageResult;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(PageResult<T> page) {
        return new PageResponse<>(page.items(), page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
