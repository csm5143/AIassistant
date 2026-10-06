package com.aiproject.aiassitant.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Pagination envelope used by every list-returning endpoint. Wraps MyBatis-Plus IPage
 * and a sliced List so the wire format is stable and frontend-friendly.
 */
@Data
@NoArgsConstructor
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Current page number (1-based)")
    private long current;

    @Schema(description = "Page size")
    private long size;

    @Schema(description = "Total record count")
    private long total;

    @Schema(description = "Total page count")
    private long pages;

    @Schema(description = "Records for the current page")
    private List<T> records;

    public PageResult(long current, long size, long total, List<T> records) {
        this.current = current;
        this.size = size;
        this.total = total;
        this.pages = size == 0 ? 0 : (total + size - 1) / size;
        this.records = records == null ? Collections.emptyList() : records;
    }

    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public static <T> PageResult<T> empty(long current, long size) {
        return new PageResult<>(current, size, 0, Collections.emptyList());
    }
}
