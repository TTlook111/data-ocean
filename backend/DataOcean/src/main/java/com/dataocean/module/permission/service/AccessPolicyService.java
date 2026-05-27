package com.dataocean.module.permission.service;

import com.dataocean.module.permission.entity.dto.AccessPolicyBatchDTO;
import com.dataocean.module.permission.entity.dto.AccessPolicyCreateDTO;
import com.dataocean.module.permission.entity.vo.AccessPolicyVO;

import java.util.List;

/**
 * 行列级访问策略服务接口
 * <p>
 * 提供细粒度的表级、列级、行级访问控制策略的增删改查。
 * </p>
 *
 * @author dataocean
 */
public interface AccessPolicyService {

    /**
     * 创建策略
     *
     * @param dto 策略创建请求
     * @return 策略 ID
     */
    Long create(AccessPolicyCreateDTO dto);

    /**
     * 批量创建策略
     *
     * @param dto 批量创建请求
     * @return 创建数量
     */
    int batchCreate(AccessPolicyBatchDTO dto);

    /**
     * 更新策略
     *
     * @param id         策略 ID
     * @param accessType 访问类型
     * @param maskStrategy 脱敏策略
     * @param rowFilterExpression 行级过滤表达式
     */
    void update(Long id, String accessType, String maskStrategy, String rowFilterExpression);

    /**
     * 删除策略
     *
     * @param id 策略 ID
     */
    void delete(Long id);

    /**
     * 查询策略列表
     *
     * @param datasourceId 数据源 ID
     * @param subjectType  主体类型（可选）
     * @param subjectId    主体 ID（可选）
     * @param tableName    表名（可选）
     * @return 策略视图列表
     */
    List<AccessPolicyVO> list(Long datasourceId, String subjectType, Long subjectId, String tableName);
}
