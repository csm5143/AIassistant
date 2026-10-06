package com.aiproject.aiassitant.module.auth.mapper;

import com.aiproject.aiassitant.module.auth.entity.SysRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

import java.util.List;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    @Select("SELECT r.* FROM sys_role r INNER JOIN sys_user_role ur ON ur.role_id = r.id WHERE ur.user_id = #{userId}")
    List<SysRole> selectByUserId(String userId);

    @Insert("INSERT IGNORE INTO sys_user_role (user_id, role_id, created_at) " +
            "SELECT #{userId}, id, NOW() FROM sys_role WHERE code = 'USER'")
    void assignUserRole(String userId);
}
