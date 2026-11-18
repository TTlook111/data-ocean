package com.dataocean.module.datasource.source;

import java.util.List;

/**
 * 两类数据库适配器共享的内部结构事实。
 */
public interface SourceMetadata {

    /**
     * 已枚举的对象。目录身份和字段均使用数据库返回原值。
     *
     * @param catalog 数据库名称
     * @param schema PostgreSQL Schema，MySQL 时为空字符串
     * @param name 对象名称
     * @param type 由实际源库结构识别的对象类型
     * @param comment 数据库声明的对象注释
     * @param identity 库可提供的对象标识，缺少时保留空值
     * @param parent 分区父对象的路径，其他对象为空
     */
    record ObjectRef(
            String catalog, String schema, String name, String type, String comment, String identity, String parent) {}

    /**
     * 一个字段的来源结构，不包含业务解释。
     *
     * @param name 源库字段名
     * @param type 包含必要参数的源库类型
     * @param nullable 字段是否允许空值
     * @param defaultValue 数据库默认表达式
     * @param generated 由数据库生成时为真
     * @param comment 来源注释
     * @param ordinal 字段顺序，从 1 开始
     */
    record Field(
            String name,
            String type,
            boolean nullable,
            String defaultValue,
            boolean generated,
            String comment,
            int ordinal) {}

    /**
     * 数据库声明的键或索引成员，同名记录按顺序组成复合键。
     *
     * @param name 键或索引名称
     * @param field 字段名，表达式索引允许为空
     * @param ordinal 成员在键内的顺序
     * @param referencedObject 外键目标路径，其他键为空
     * @param referencedField 外键目标字段
     * @param unique 唯一索引或主键为真
     */
    record Key(
            String name, String field, int ordinal, String referencedObject, String referencedField, boolean unique) {}

    /**
     * 完成某对象必要属性读取后得到的候选结构。
     *
     * @param object 对象标识和注释
     * @param fields 字段清单
     * @param primaryKeys 主键成员
     * @param foreignKeys 外键成员
     * @param indexes 适用的物理索引成员
     */
    record Structure(
            ObjectRef object, List<Field> fields, List<Key> primaryKeys, List<Key> foreignKeys, List<Key> indexes) {}

    /**
     * 可用于观察原会话的身份。会话号复用时还须比对随机标记和启动时刻。
     *
     * @param instance 来源实例身份
     * @param sessionId 源端会话编号
     * @param started 会话启动时刻，MySQL 通过连接属性比对
     * @param marker 每次连接生成的随机标记
     */
    record Session(String instance, long sessionId, String started, String marker) {}
}
