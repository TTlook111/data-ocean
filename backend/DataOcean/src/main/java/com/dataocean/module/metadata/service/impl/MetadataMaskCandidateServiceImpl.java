package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataMaskCandidateService;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.dto.IamS1FieldProtectionSaveDTO;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
import com.dataocean.module.permission.s1.service.IamS1FieldProtectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 掩码候选确认/拒绝编排实现。
 *
 * <p>S1 字段保护写入与候选标记清理必须在同一事务：任一失败都整体回滚，
 * 不允许出现“保护已生效但候选还在”（重复确认会再插一条）或“候选清掉了但保护没生效”。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataMaskCandidateServiceImpl implements MetadataMaskCandidateService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final MetadataEntityService entityService;
    private final DbColumnMetaMapper columnMetaMapper;
    private final IamS1FieldProtectionMapper fieldProtectionMapper;
    private final IamS1FieldProtectionService fieldProtectionService;

    @Override
    @Transactional
    public void confirm(Long operatorUserId, Long entityId, String maskStrategy) {
        if (maskStrategy == null || maskStrategy.isBlank()) {
            throw new BusinessException(400, "掩码策略不能为空");
        }
        MaskCandidateTarget target = resolveTarget(requireColumnEntity(entityId));

        // 1. 先锁住字段元数据行，把并发的两次确认在同一把锁上串行化。
        //    缺少这一步时两个请求会各自读到“没有 ACTIVE 同策略保护”并各插一条：
        //    顺序调用幂等，并发不幂等（V55 没有唯一约束兜底）。
        DbColumnMeta column = requireColumnMetaForUpdate(target);

        // 2. 取得锁之后再读候选状态。这里必须是当前读：MySQL 默认 REPEATABLE READ 下
        //    普通 SELECT 读的是事务开始时的快照，看不到等待锁期间另一个事务的提交。
        MetadataEntity entity = entityService.getEntityByIdForUpdate(entityId);
        if (entity == null || !hasPendingMask(entity)) {
            // 候选已被处理：幂等成功，不重复写入保护，也不重复清理。
            return;
        }

        // 3. 锁内重新读取 ACTIVE 保护。
        // V55 的 iam_s1_field_protection 没有唯一约束，重复确认会累积多条 ACTIVE 记录，
        // 让 Resolver 取值依赖插入顺序。
        List<IamS1FieldProtection> existing = fieldProtectionMapper.selectList(
                new LambdaQueryWrapper<IamS1FieldProtection>()
                        .eq(IamS1FieldProtection::getProtocolVersion, IamS1Constants.PROTOCOL_VERSION)
                        .eq(IamS1FieldProtection::getDatasourceId, target.datasourceId())
                        .eq(IamS1FieldProtection::getMetadataSnapshotId, target.snapshotId())
                        .eq(IamS1FieldProtection::getColumnMetaId, column.getId())
                        .eq(IamS1FieldProtection::getStatus, STATUS_ACTIVE));
        boolean samePolicyAlreadyActive = existing.stream()
                .anyMatch(item -> maskStrategy.equalsIgnoreCase(item.getMaskPolicy()));

        if (!samePolicyAlreadyActive) {
            // 策略不同时先撤销旧的同列保护，避免多条 ACTIVE 并存。
            for (IamS1FieldProtection item : existing) {
                fieldProtectionService.revokeProtection(operatorUserId, item.getId(),
                        "掩码候选重新确认，替换旧保护");
            }
            IamS1FieldProtectionSaveDTO protection = new IamS1FieldProtectionSaveDTO();
            protection.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
            protection.setDatasourceId(target.datasourceId());
            protection.setMetadataSnapshotId(target.snapshotId());
            protection.setTableName(target.tableName());
            protection.setColumnMetaId(column.getId());
            protection.setColumnName(target.columnName());
            protection.setProtectionLevel(IamS1Constants.PROTECTION_MASKED);
            protection.setMaskPolicy(maskStrategy);
            protection.setStatus(STATUS_ACTIVE);
            protection.setReason("由字段治理掩码候选确认");
            fieldProtectionService.saveProtection(operatorUserId, protection);
        }

        // 清理候选标记：**不捕获异常**。它与上面的写入同处一个事务，
        // 失败必须让整个确认回滚，否则接口报成功而候选仍挂着。
        clearPendingMask(entity);
    }

    @Override
    @Transactional
    public void reject(Long operatorUserId, Long entityId) {
        MaskCandidateTarget target = resolveTarget(requireColumnEntity(entityId));
        // 与 confirm 抢同一把锁：否则“确认”和“拒绝”并发时可能一个写保护、另一个清标记，
        // 结果既不确定也不可复现。
        requireColumnMetaForUpdate(target);
        MetadataEntity entity = entityService.getEntityByIdForUpdate(entityId);
        if (entity == null || !hasPendingMask(entity)) {
            return;
        }
        clearPendingMask(entity);
    }

    private MetadataEntity requireColumnEntity(Long entityId) {
        MetadataEntity entity = entityId == null ? null : entityService.getById(entityId);
        if (entity == null || !MetadataEntity.TYPE_COLUMN.equals(entity.getEntityType())) {
            throw new BusinessException(404, "列实体不存在");
        }
        return entity;
    }

    /** 从实体元数据与 FQN 解析出数据源、快照与表名。 */
    private MaskCandidateTarget resolveTarget(MetadataEntity entity) {
        Long datasourceId = null;
        Long snapshotId = null;
        try {
            var node = OBJECT_MAPPER.readTree(entity.getEntityMetadata());
            if (node.has("datasource_id")) {
                datasourceId = node.get("datasource_id").asLong();
            }
            if (node.has("snapshot_id")) {
                snapshotId = node.get("snapshot_id").asLong();
            }
        } catch (Exception exception) {
            log.warn("解析实体元数据失败 entityId={}: {}", entity.getId(), exception.getMessage());
        }
        // FQN 形如 datasource.db.table.column → 取倒数第二段作为表名
        String fqn = entity.getFqn();
        String[] parts = fqn == null ? new String[0] : fqn.split("\\.");
        String tableName = parts.length >= 3 ? parts[parts.length - 2] : null;
        if (datasourceId == null || snapshotId == null || tableName == null) {
            throw new BusinessException(400, "无法从实体元数据解析数据源、快照或表名");
        }
        return new MaskCandidateTarget(datasourceId, snapshotId, tableName, entity.getName());
    }

    /**
     * 取字段元数据行并加锁。
     *
     * <p>S1 字段保护按 column_meta_id 关联，而实体元数据里没有该 ID，必须回查；
     * 回查时用 `FOR UPDATE` 把并发确认串行化。</p>
     */
    private DbColumnMeta requireColumnMetaForUpdate(MaskCandidateTarget target) {
        DbColumnMeta column = columnMetaMapper.selectForUpdate(
                target.snapshotId(), target.tableName(), target.columnName());
        if (column == null || column.getId() == null) {
            throw new BusinessException(404, "找不到对应的字段元数据，无法建立字段保护");
        }
        return column;
    }

    private boolean hasPendingMask(MetadataEntity entity) {
        try {
            var node = OBJECT_MAPPER.readTree(entity.getEntityMetadata());
            return node != null && node.has("pending_mask");
        } catch (Exception exception) {
            log.warn("解析候选标记失败 entityId={}: {}", entity.getId(), exception.getMessage());
            return false;
        }
    }

    private void clearPendingMask(MetadataEntity entity) {
        try {
            var node = OBJECT_MAPPER.readTree(entity.getEntityMetadata());
            if (node instanceof ObjectNode objectNode) {
                objectNode.remove("pending_mask");
                entity.setEntityMetadata(OBJECT_MAPPER.writeValueAsString(objectNode));
                if (!entityService.updateById(entity)) {
                    // 更新 0 行说明行已被别的事务改动：必须回滚，
                    // 否则会留下“保护写入成功但候选没清掉”的不一致状态。
                    throw new BusinessException(409, "掩码候选已被其他操作更新，请刷新后重试");
                }
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            // 转成业务异常而不是吞掉：调用方在同一事务内，需要它回滚。
            throw new BusinessException(500, "清除掩码候选标记失败：" + exception.getMessage());
        }
    }

    /** 解析出的候选目标。 */
    private record MaskCandidateTarget(Long datasourceId, Long snapshotId, String tableName, String columnName) {
    }
}
