package com.dataocean.module.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 元数据质量规则 Mapper 接口。
 */
@Mapper
public interface MetadataQualityRuleMapper extends BaseMapper<MetadataQualityRule> {
    /** 统一锁序在读取规则原范围之前；同级启用冲突不依赖空结果集的间隙锁。 */
    @Select("SELECT id FROM quality_rule_write_guard WHERE id = 1 FOR UPDATE")
    Integer lockConfigurationWrites();

    /** 写入先锁定实际原范围；复制同样需要固定源版本，避免读到变化中的配置。 */
    @Select("SELECT * FROM metadata_quality_rule WHERE id = #{ruleId} FOR UPDATE")
    MetadataQualityRule selectForUpdate(Long ruleId);
}
