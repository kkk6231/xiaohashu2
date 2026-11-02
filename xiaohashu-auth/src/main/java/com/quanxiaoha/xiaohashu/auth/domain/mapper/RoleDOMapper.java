package com.quanxiaoha.xiaohashu.auth.domain.mapper;

import com.quanxiaoha.xiaohashu.auth.domain.dataobject.RoleDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface RoleDOMapper {
    int deleteByPrimaryKey(Long id);

    int insert(RoleDO record);

    int insertSelective(RoleDO record);

    RoleDO selectByPrimaryKey(Long id);

    /**
     * 根据角色标识查询有效角色。
     *
     * @param roleKey 角色标识
     * @return 有效角色，不存在时返回 null
     */
    RoleDO selectByRoleKey(@Param("roleKey") String roleKey);

    /**
     * 查询所有被启用的角色
     *
     * @return
     */
    List<RoleDO> selectEnabledList();

    int updateByPrimaryKeySelective(RoleDO record);

    int updateByPrimaryKey(RoleDO record);

}
