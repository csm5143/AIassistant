package com.aiproject.aiassitant.module.chat.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.LocalDate;

@Data
@TableName("user_quota")
public class UserQuota implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String userId;
    private String tier;
    private Long dailyTokenLimit;
    private Long monthlyTokenLimit;
    private Long dailyTokenUsed;
    private Long monthlyTokenUsed;
    private Integer dailyRequestLimit;
    private Integer monthlyRequestLimit;
    private Integer dailyRequestUsed;
    private Integer monthlyRequestUsed;
    private LocalDateTime quotaResetAt;
    private LocalDate monthlyResetAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean hasUnlimitedTokens() {
        return dailyTokenLimit == -1L;
    }

    public boolean hasUnlimitedRequests() {
        return dailyRequestLimit == -1;
    }

    public boolean canUseTokens(long needed) {
        if (hasUnlimitedTokens()) return true;
        return (dailyTokenUsed + needed) <= dailyTokenLimit;
    }

    public boolean canMakeRequest() {
        if (hasUnlimitedRequests()) return true;
        return dailyRequestUsed < dailyRequestLimit;
    }
}
