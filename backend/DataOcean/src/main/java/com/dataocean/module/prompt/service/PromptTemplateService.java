package com.dataocean.module.prompt.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.prompt.entity.dto.*;

import java.util.List;

/**
 * Prompt 模板服务接口
 * <p>
 * 提供模板的增删改查、版本管理和回滚能力。
 * </p>
 */
public interface PromptTemplateService {

    /**
     * 分页查询模板列表
     *
     * @param page     页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    Page<PromptTemplateVO> listTemplates(int page, int pageSize);

    /**
     * 根据编码获取模板详情
     *
     * @param code 模板编码
     * @return 模板视图对象
     */
    PromptTemplateVO getTemplate(String code);

    /**
     * 更新模板内容（自动创建新版本）
     *
     * @param code    模板编码
     * @param request 更新请求
     * @return 更新后的模板视图对象
     */
    PromptTemplateVO updateTemplate(String code, PromptTemplateUpdateDTO request);

    /**
     * 获取模板的版本历史
     *
     * @param code 模板编码
     * @return 版本列表（按版本号降序）
     */
    List<PromptVersionVO> getVersionHistory(String code);

    /**
     * 回滚到指定版本
     *
     * @param code    模板编码
     * @param request 回滚请求
     * @return 回滚后的模板视图对象
     */
    PromptTemplateVO rollback(String code, PromptRollbackDTO request);

    /**
     * 获取活跃版本的模板内容（供 Python 服务调用）
     *
     * @param code 模板编码
     * @return 模板内容字符串
     */
    String getActiveContent(String code);
}
