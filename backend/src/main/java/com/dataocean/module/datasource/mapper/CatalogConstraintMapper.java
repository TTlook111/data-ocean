package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.po.CatalogConstraintPO;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 保存对象约束和复合主键约束成员。
 */
public interface CatalogConstraintMapper extends BaseMapper<CatalogConstraintPO> {

    /**
     * 按来源次序记录字段成员及原外键目标路径。
     */
    @Insert(
            """
            INSERT INTO ds_constraint_member (constraint_id,column_revision_id,ordinal,target_column_ref)
            VALUES (#{constraint},#{column},#{ordinal},#{target})
            """)
    int addMember(
            @Param("constraint") long constraint,
            @Param("column") Long column,
            @Param("ordinal") int ordinal,
            @Param("target") String target);
}
