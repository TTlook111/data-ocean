package com.dataocean.module.fieldsemantic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FieldSemanticDefinitionMapper extends BaseMapper<FieldSemanticDefinition> {
    @Select("SELECT * FROM field_semantic_definition WHERE id = #{id} FOR UPDATE")
    FieldSemanticDefinition selectForUpdate(@Param("id") Long id);
}
