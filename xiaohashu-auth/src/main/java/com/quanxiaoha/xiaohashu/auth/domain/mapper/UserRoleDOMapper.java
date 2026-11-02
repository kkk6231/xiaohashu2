package com.quanxiaoha.xiaohashu.auth.domain.mapper;

import com.quanxiaoha.xiaohashu.auth.domain.dataobject.UserRoleDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserRoleDOMapper {
    int deleteByPrimaryKey(Long id);

    int insert(UserRoleDO record);

    int insertSelective(UserRoleDO record);

    UserRoleDO selectByPrimaryKey(Long id);

    /**
     * 查询用户当前有效的角色标识。
     *
     * @param userId 用户 ID
     * @return 角色标识集合
     */
    List<String> selectRoleKeysByUserId(@Param("userId") Long userId);

    /**
     * 删除用户的普通用户和受限用户角色关系。
     *
     * @param userId 用户 ID
     * @return 影响行数
     */
    int deleteAppUserRoles(@Param("userId") Long userId);

    int updateByPrimaryKeySelective(UserRoleDO record);

    int updateByPrimaryKey(UserRoleDO record);
}
