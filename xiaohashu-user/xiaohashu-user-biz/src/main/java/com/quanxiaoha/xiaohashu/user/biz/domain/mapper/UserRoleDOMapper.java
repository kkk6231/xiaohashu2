package com.quanxiaoha.xiaohashu.user.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.user.biz.domain.dataobject.UserRoleDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserRoleDOMapper {
    int deleteByPrimaryKey(Long id);

    int insert(UserRoleDO record);

    int insertSelective(UserRoleDO record);

    UserRoleDO selectByPrimaryKey(Long id);

    /**
     * 查询用户当前拥有的有效角色标识。
     *
     * @param userId 用户 ID
     * @return 有效角色标识集合
     */
    List<String> selectRoleKeysByUserId(@Param("userId") Long userId);

    int updateByPrimaryKeySelective(UserRoleDO record);

    int updateByPrimaryKey(UserRoleDO record);
}
