package com.aiproject.aiassitant.common;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Canonical pagination query. All list endpoints should accept this bean (or extend it)
 * so pagination is uniform across the platform.
 */
@Data
public class PageQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "1-based page number", example = "1")
    @Min(1)
    private long current = 1;

    @Schema(description = "Page size (1-200)", example = "10")
    @Min(1)
    @Max(200)
    private long size = 10;

    @Schema(description = "Sort field", example = "createdAt")
    private String sortField;

    @Schema(description = "Sort direction: asc/desc", example = "desc")
    private String sortOrder = "desc";
}
