package com.aiproject.aiassitant.module.chat.mapper;

import com.aiproject.aiassitant.module.chat.entity.TokenUsageLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TokenUsageLogMapper extends BaseMapper<TokenUsageLog> {
    @Insert("INSERT INTO token_usage_log (id,user_id,date_key,model,prompt_tokens,completion_tokens,total_tokens,request_count,cost_usd,created_at,updated_at) " +
            "VALUES (#{id},#{userId},#{dateKey},#{model},#{prompt},#{completion},#{total},1,#{cost},NOW(),NOW()) " +
            "ON DUPLICATE KEY UPDATE prompt_tokens=prompt_tokens+VALUES(prompt_tokens), " +
            "completion_tokens=completion_tokens+VALUES(completion_tokens), " +
            "total_tokens=total_tokens+VALUES(total_tokens), request_count=request_count+1, " +
            "cost_usd=cost_usd+VALUES(cost_usd), updated_at=NOW()")
    void addUsage(@Param("id") String id, @Param("userId") String userId,
                  @Param("dateKey") java.time.LocalDate dateKey, @Param("model") String model,
                  @Param("prompt") long prompt, @Param("completion") long completion,
                  @Param("total") long total, @Param("cost") java.math.BigDecimal cost);
}
