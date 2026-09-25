package com.dataocean.common.security;

import java.util.List;
import java.util.Map;

/**
 * 数据脱敏服务接口
 * <p>
 * 在查询结果返回前端之前，对敏感列执行脱敏。这是 Java 侧的最终保护：
 * 即使上游（RAG / Prompt / Python AST）未能正确标记，Java 也会按精确字段映射再脱一次。
 * </p>
 * <p>
 * B6 批次 3：由 {@code module.permission.service} 迁入，并删除了绑定旧
 * {@code PermissionContextVO} 的 {@code maskResult(List, List&lt;MaskColumnItem&gt;)} 重载
 * （其唯一调用方是旧问数链路的 {@code QueryTaskServiceImpl}，已一并删除）。
 * 现有两个方法只依赖「输出列名 → 策略名」的映射，不含任何权限模型语义。
 * </p>
 *
 * @author dataocean
 */
public interface DataMaskingService {

    /**
     * 对查询结果执行精确脱敏（基于 Python AST 标记的实际字段）
     * <p>
     * Python 在 SQL AST 分析阶段已精确识别本次查询实际涉及的脱敏字段（含别名解析），
     * 返回 {输出列名 → 脱敏策略} 映射，Java 直接按结果 key 匹配执行脱敏。
     * </p>
     *
     * @param data         查询结果数据行列表
     * @param maskedFields Python 标记的 {输出列名 → 策略名} 映射
     * @return 脱敏后的数据
     */
    List<Map<String, Object>> maskResultByFields(List<Map<String, Object>> data,
                                                  Map<String, String> maskedFields);

    /**
     * 对单个值执行脱敏
     *
     * @param value    原始值
     * @param strategy 脱敏策略名称
     * @return 脱敏后的值
     */
    String maskValue(String value, String strategy);
}
