package com.dataocean.module.permission.service;

import com.dataocean.module.permission.entity.vo.PermissionContextVO;

import java.util.List;
import java.util.Map;

/**
 * 数据脱敏服务接口
 * <p>
 * 在查询结果返回前端之前，根据权限上下文中的 maskColumns 配置，
 * 对指定列执行脱敏处理。
 * </p>
 *
 * @author dataocean
 */
public interface DataMaskingService {

    /**
     * 对查询结果执行脱敏
     *
     * @param data        查询结果数据行列表
     * @param maskColumns 需要脱敏的列配置
     * @return 脱敏后的数据
     */
    List<Map<String, Object>> maskResult(List<Map<String, Object>> data,
                                          List<PermissionContextVO.MaskColumnItem> maskColumns);

    /**
     * 对单个值执行脱敏
     *
     * @param value    原始值
     * @param strategy 脱敏策略名称
     * @return 脱敏后的值
     */
    String maskValue(String value, String strategy);
}
