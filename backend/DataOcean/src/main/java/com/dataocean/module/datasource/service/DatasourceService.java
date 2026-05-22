package com.dataocean.module.datasource.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.datasource.entity.query.DatasourceQuery;
import com.dataocean.module.datasource.entity.dto.DatasourceCreateDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceTestDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceUpdateDTO;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestVO;
import com.dataocean.module.datasource.entity.vo.DatasourceVO;

/**
 * 数据源管理服务接口
 * <p>
 * 提供数据源的完整生命周期管理，包括创建、更新、删除、查询、
 * 状态变更和连接测试等核心业务操作。
 * </p>
 *
 * @author dataocean
 */
public interface DatasourceService {

    /**
     * 创建数据源
     * <p>
     * 创建前会执行连接测试，测试通过后保存数据源信息和加密凭证。
     * </p>
     *
     * @param request 创建请求参数
     * @return 创建后的数据源详情
     */
    DatasourceVO createDatasource(DatasourceCreateDTO request);

    /**
     * 更新数据源
     * <p>
     * 更新前会使用新参数执行连接测试，测试通过后更新数据源信息和凭证。
     * 若密码变更，会通知 Python 服务销毁旧连接池。
     * </p>
     *
     * @param id      数据源 ID
     * @param request 更新请求参数
     * @return 更新后的数据源详情
     */
    DatasourceVO updateDatasource(Long id, DatasourceUpdateDTO request);

    /**
     * 软删除数据源
     * <p>
     * 删除前检查是否存在已发布的元数据快照或 skills.md，
     * 存在则拒绝删除。删除后通知 Python 服务销毁连接池。
     * </p>
     *
     * @param id 数据源 ID
     */
    void deleteDatasource(Long id);

    /**
     * 根据 ID 获取数据源详情
     *
     * @param id 数据源 ID
     * @return 数据源详情视图对象
     */
    DatasourceVO getDatasourceById(Long id);

    /**
     * 分页查询数据源列表
     *
     * @param request 查询条件（名称模糊、状态、健康状态、分页参数）
     * @return 分页结果
     */
    Page<DatasourceVO> listDatasources(DatasourceQuery request);

    /**
     * 更新数据源启用/禁用状态
     * <p>
     * 启用时会先执行连接测试，测试失败则拒绝启用。
     * 禁用时通知 Python 服务销毁连接池。
     * </p>
     *
     * @param id     数据源 ID
     * @param status 目标状态：0-禁用，1-启用
     * @return 更新后的数据源详情
     */
    DatasourceVO updateStatus(Long id, Integer status);

    /**
     * 使用指定参数测试数据库连接（不保存）
     *
     * @param request 连接测试请求参数
     * @return 连接测试结果
     */
    DatasourceConnectionTestVO testConnection(DatasourceTestDTO request);

    /**
     * 对已保存的数据源执行连接测试
     * <p>
     * 使用数据库中存储的凭证进行测试，测试成功会更新健康状态。
     * </p>
     *
     * @param id 数据源 ID
     * @return 连接测试结果
     */
    DatasourceConnectionTestVO testSavedConnection(Long id);
}
