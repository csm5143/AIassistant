package com.aiproject.aiassitant.module.chat.mapper;

import com.aiproject.aiassitant.module.chat.entity.UserQuota;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserQuotaMapper extends BaseMapper<UserQuota> {

    /** Pessimistic read lock to prevent concurrent quota bypass. */
    @Select("SELECT * FROM user_quota WHERE user_id = #{userId} FOR UPDATE")
    UserQuota selectOneForUpdate(String userId);
}
