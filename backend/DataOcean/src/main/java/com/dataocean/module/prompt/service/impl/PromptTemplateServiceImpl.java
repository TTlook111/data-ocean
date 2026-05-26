package com.dataocean.module.prompt.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.prompt.entity.PromptTemplate;
import com.dataocean.module.prompt.entity.PromptTemplateVersion;
import com.dataocean.module.prompt.entity.dto.*;
import com.dataocean.module.prompt.mapper.PromptTemplateMapper;
import com.dataocean.module.prompt.mapper.PromptTemplateVersionMapper;
import com.dataocean.module.prompt.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Prompt 模板服务实现类
 * <p>
 * 实现模板的 CRUD、版本管理和回滚逻辑。
 * 更新操作自动创建新版本，支持按版本号回滚。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromptTemplateServiceImpl implements PromptTemplateService {

    private final PromptTemplateMapper templateMapper;
    private final PromptTemplateVersionMapper versionMapper;

    /**
     * 分页查询模板列表，按场景升序排列
     */
    @Override
    public Page<PromptTemplateVO> listTemplates(int page, int pageSize) {
        Page<PromptTemplate> result = templateMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<PromptTemplate>().orderByAsc(PromptTemplate::getScenario));
        Page<PromptTemplateVO> voPage = new Page<>(page, pageSize, result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return voPage;
    }

    /**
     * 根据编码获取模板详情
     */
    @Override
    public PromptTemplateVO getTemplate(String code) {
        PromptTemplate template = getByCode(code);
        return toVO(template);
    }

    /**
     * 更新模板内容，自动创建新版本并将旧版本标记为非活跃
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplateVO updateTemplate(String code, PromptTemplateUpdateDTO request) {
        PromptTemplate template = getByCode(code);
        int newVersionNo = template.getCurrentVersion() + 1;

        // 步骤1：将旧活跃版本设为非活跃
        versionMapper.update(null, new LambdaUpdateWrapper<PromptTemplateVersion>()
                .eq(PromptTemplateVersion::getTemplateId, template.getId())
                .eq(PromptTemplateVersion::getIsActive, true)
                .set(PromptTemplateVersion::getIsActive, false));

        // 步骤2：创建新版本记录
        PromptTemplateVersion newVersion = new PromptTemplateVersion();
        newVersion.setTemplateId(template.getId());
        newVersion.setVersionNo(newVersionNo);
        newVersion.setContent(request.getContent());
        newVersion.setChangeSummary(request.getChangeSummary());
        newVersion.setIsActive(true);
        newVersion.setCreatedBy(UserContext.currentUserId());
        newVersion.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(newVersion);

        // 步骤3：更新模板主表的内容和版本号（乐观锁冲突时返回 0 行）
        template.setContent(request.getContent());
        template.setCurrentVersion(newVersionNo);
        template.setUpdatedAt(LocalDateTime.now());
        int updated = templateMapper.updateById(template);
        if (updated == 0) {
            throw new BusinessException(409, "模板更新冲突，其他用户正在编辑，请刷新后重试");
        }

        log.info("Prompt 模板更新 code={} newVersion={}", code, newVersionNo);
        return toVO(template);
    }

    /**
     * 获取模板的版本历史，按版本号降序排列
     */
    @Override
    public List<PromptVersionVO> getVersionHistory(String code) {
        PromptTemplate template = getByCode(code);
        List<PromptTemplateVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<PromptTemplateVersion>()
                        .eq(PromptTemplateVersion::getTemplateId, template.getId())
                        .orderByDesc(PromptTemplateVersion::getVersionNo));
        return versions.stream().map(this::toVersionVO).collect(Collectors.toList());
    }

    /**
     * 回滚到指定版本：将目标版本设为活跃，更新主表内容
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplateVO rollback(String code, PromptRollbackDTO request) {
        PromptTemplate template = getByCode(code);

        // 步骤1：查找目标版本
        PromptTemplateVersion targetVersion = versionMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplateVersion>()
                        .eq(PromptTemplateVersion::getTemplateId, template.getId())
                        .eq(PromptTemplateVersion::getVersionNo, request.getTargetVersionNo()));
        if (targetVersion == null) {
            throw new BusinessException("目标版本不存在：v" + request.getTargetVersionNo());
        }

        // 步骤2：将当前活跃版本设为非活跃
        versionMapper.update(null, new LambdaUpdateWrapper<PromptTemplateVersion>()
                .eq(PromptTemplateVersion::getTemplateId, template.getId())
                .eq(PromptTemplateVersion::getIsActive, true)
                .set(PromptTemplateVersion::getIsActive, false));

        // 步骤3：将目标版本设为活跃
        targetVersion.setIsActive(true);
        versionMapper.updateById(targetVersion);

        // 步骤4：更新模板主表（乐观锁冲突检测）
        template.setContent(targetVersion.getContent());
        template.setCurrentVersion(request.getTargetVersionNo());
        template.setUpdatedAt(LocalDateTime.now());
        int updated = templateMapper.updateById(template);
        if (updated == 0) {
            throw new BusinessException(409, "模板回滚冲突，其他用户正在编辑，请刷新后重试");
        }

        log.info("Prompt 模板回滚 code={} targetVersion={}", code, request.getTargetVersionNo());
        return toVO(template);
    }

    /**
     * 获取活跃版本的模板内容（供 Python 服务内部调用）
     */
    @Override
    public String getActiveContent(String code) {
        PromptTemplate template = templateMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplate>()
                        .eq(PromptTemplate::getTemplateCode, code)
                        .eq(PromptTemplate::getEnabled, true));
        if (template == null) {
            throw new BusinessException(404, "Prompt 模板不存在或已禁用：" + code);
        }
        return template.getContent();
    }

    /**
     * 根据编码查询模板，不存在则抛出异常
     */
    private PromptTemplate getByCode(String code) {
        PromptTemplate template = templateMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplate>()
                        .eq(PromptTemplate::getTemplateCode, code));
        if (template == null) {
            throw new BusinessException(404, "Prompt 模板不存在：" + code);
        }
        return template;
    }

    /**
     * 实体转视图对象
     */
    private PromptTemplateVO toVO(PromptTemplate entity) {
        PromptTemplateVO vo = new PromptTemplateVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    /**
     * 版本实体转版本视图对象
     */
    private PromptVersionVO toVersionVO(PromptTemplateVersion entity) {
        PromptVersionVO vo = new PromptVersionVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
