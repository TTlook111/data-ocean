package com.dataocean.module.permission.s1.entity.vo;

/**
 * 选择对象用的最小信息。打开授权表单只需要必要名称，不要求“查看全部用户”。
 */
public record IamS1SubjectOptionVO(Long id, String name, String subjectType, String subjectTypeName) {
}
