package com.dataocean.module.fieldsemantic.service.impl;
import java.util.*;

/** 产品可读的起步检查集，复用真实执行策略版本，不允许前端伪造可扩展执行器。 */
public final class GovernanceRuleCatalog {
    private GovernanceRuleCatalog(){}
    public static List<Map<String,Object>> rules(){
        var result=new ArrayList<Map<String,Object>>();
        result.add(rule("DATASOURCE_PURPOSE","数据源用途与范围","数据源","业务说明、适用范围与人工依据"));
        result.add(rule("TABLE_GRAIN","表用途与粒度","表","业务职责及一行数据代表什么"));
        result.add(rule("RELATION_USAGE","表关系与聚合条件","关系","完整关联字段、关系基数、避免重复计数的方式"));
        result.add(rule("METRIC_DEFINITION","指标口径","指标","计算公式、必要条件和完整依赖；无筛选行计数可仅依赖表"));
        result.add(rule("FIELD_SEMANTIC","字段业务解释","字段","当前来源、已审核修订和有效复查证据"));
        result.add(rule("FIELD_CLASSIFICATION","字段分类标签","字段","独立维护的字段分类"));
        result.add(rule("FIELD_ROLE_DETAILS","分类对应的必要信息","字段","金额类需单位；枚举/状态类需枚举含义，其他类型不强填"));
        return List.copyOf(result);
    }
    private static Map<String,Object> rule(String code,String name,String target,String requirement){return Map.of("code",code,"name",name,"target",target,
            "requirement",requirement,"version",SemanticCheckServiceImpl.RULE_VERSION,"configurable",false);}
}
