package com.dataocean.module.fieldtag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldtag.entity.FieldTag;
import com.dataocean.module.fieldtag.entity.PredefinedTag;
import com.dataocean.module.fieldtag.entity.dto.BatchTagRequestDTO;
import com.dataocean.module.fieldtag.entity.dto.FieldTagRequestDTO;
import com.dataocean.module.fieldtag.entity.vo.FieldTagVO;
import com.dataocean.module.fieldtag.mapper.FieldTagMapper;
import com.dataocean.module.fieldtag.mapper.PredefinedTagMapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 字段标签服务实现类
 * <p>
 * 实现字段标签的增删查操作，包含标签编码校验和字段存在性校验。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FieldTagServiceImpl implements com.dataocean.module.fieldtag.service.FieldTagService {

    private final FieldTagMapper fieldTagMapper;
    private final PredefinedTagMapper predefinedTagMapper;
    private final DbColumnMetaMapper dbColumnMetaMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FieldTagVO addTag(FieldTagRequestDTO request) {
        // 校验字段是否存在
        validateColumnExists(request.getColumnMetaId());
        // 校验标签编码是否在预定义列表中
        PredefinedTag predefinedTag = validateTagCode(request.getTagCode());
        // 检查是否已存在相同标签
        Long count = fieldTagMapper.selectCount(
                new LambdaQueryWrapper<FieldTag>()
                        .eq(FieldTag::getColumnMetaId, request.getColumnMetaId())
                        .eq(FieldTag::getTagCode, request.getTagCode())
        );
        if (count > 0) {
            throw new BusinessException("该字段已存在此标签");
        }
        // 创建标签记录
        FieldTag tag = new FieldTag();
        tag.setColumnMetaId(request.getColumnMetaId());
        tag.setTagCode(request.getTagCode());
        tag.setTagName(predefinedTag.getTagName());
        tag.setSource("MANUAL");
        tag.setCreatedBy(UserContext.currentUserId());
        tag.setCreatedAt(LocalDateTime.now());
        fieldTagMapper.insert(tag);
        log.info("为字段 {} 添加标签 {}", request.getColumnMetaId(), request.getTagCode());
        return toVO(tag);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchAddTags(BatchTagRequestDTO request) {
        // 校验标签编码
        PredefinedTag predefinedTag = validateTagCode(request.getTagCode());
        Long currentUserId = UserContext.currentUserId();
        List<Long> columnMetaIds = request.getColumnMetaIds();
        // 批量查询哪些字段实际存在
        List<DbColumnMeta> existingColumns = dbColumnMetaMapper.selectBatchIds(columnMetaIds);
        Set<Long> existingColumnIds = existingColumns.stream()
                .map(DbColumnMeta::getId)
                .collect(Collectors.toSet());
        // 批量查询哪些字段已有该标签
        List<FieldTag> existingTags = fieldTagMapper.selectList(
                new LambdaQueryWrapper<FieldTag>()
                        .in(FieldTag::getColumnMetaId, columnMetaIds)
                        .eq(FieldTag::getTagCode, request.getTagCode())
        );
        Set<Long> alreadyTaggedIds = existingTags.stream()
                .map(FieldTag::getColumnMetaId)
                .collect(Collectors.toSet());
        // 构建待插入列表（仅包含存在且未打标的字段）
        List<FieldTag> tagsToInsert = new ArrayList<>();
        for (Long columnMetaId : columnMetaIds) {
            if (!existingColumnIds.contains(columnMetaId)) {
                log.warn("批量打标跳过不存在的字段 columnMetaId={}", columnMetaId);
                continue;
            }
            if (alreadyTaggedIds.contains(columnMetaId)) {
                continue;
            }
            FieldTag tag = new FieldTag();
            tag.setColumnMetaId(columnMetaId);
            tag.setTagCode(request.getTagCode());
            tag.setTagName(predefinedTag.getTagName());
            tag.setSource("MANUAL");
            tag.setCreatedBy(currentUserId);
            tag.setCreatedAt(LocalDateTime.now());
            tagsToInsert.add(tag);
        }
        if (tagsToInsert.isEmpty()) {
            return 0;
        }
        // 批量插入
        fieldTagMapper.batchInsert(tagsToInsert);
        log.info("批量打标完成，标签={} 成功数量={}", request.getTagCode(), tagsToInsert.size());
        return tagsToInsert.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeTag(Long id) {
        FieldTag tag = fieldTagMapper.selectById(id);
        if (tag == null) {
            throw new BusinessException("标签不存在");
        }
        fieldTagMapper.deleteById(id);
        log.info("移除字段标签 id={} columnMetaId={} tagCode={}", id, tag.getColumnMetaId(), tag.getTagCode());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<FieldTagVO> getTagsByColumnMetaId(Long columnMetaId) {
        List<FieldTag> tags = fieldTagMapper.selectList(
                new LambdaQueryWrapper<FieldTag>()
                        .eq(FieldTag::getColumnMetaId, columnMetaId)
                        .orderByAsc(FieldTag::getCreatedAt)
        );
        return tags.stream().map(this::toVO).collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Long> getColumnIdsByTagCode(String tagCode) {
        List<FieldTag> tags = fieldTagMapper.selectList(
                new LambdaQueryWrapper<FieldTag>()
                        .eq(FieldTag::getTagCode, tagCode)
                        .select(FieldTag::getColumnMetaId)
        );
        return tags.stream().map(FieldTag::getColumnMetaId).collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<PredefinedTag> listPredefinedTags() {
        return predefinedTagMapper.selectList(
                new LambdaQueryWrapper<PredefinedTag>()
                        .orderByAsc(PredefinedTag::getSortOrder)
        );
    }

    /**
     * 校验字段元数据是否存在
     */
    private void validateColumnExists(Long columnMetaId) {
        if (dbColumnMetaMapper.selectById(columnMetaId) == null) {
            throw new BusinessException(404, "字段不存在，columnMetaId=" + columnMetaId);
        }
    }

    /**
     * 校验标签编码是否在预定义列表中
     */
    private PredefinedTag validateTagCode(String tagCode) {
        PredefinedTag predefinedTag = predefinedTagMapper.selectOne(
                new LambdaQueryWrapper<PredefinedTag>()
                        .eq(PredefinedTag::getTagCode, tagCode)
        );
        if (predefinedTag == null) {
            throw new BusinessException("无效的标签编码：" + tagCode);
        }
        return predefinedTag;
    }

    /**
     * 实体转视图对象
     */
    private FieldTagVO toVO(FieldTag tag) {
        FieldTagVO vo = new FieldTagVO();
        BeanUtils.copyProperties(tag, vo);
        return vo;
    }
}
