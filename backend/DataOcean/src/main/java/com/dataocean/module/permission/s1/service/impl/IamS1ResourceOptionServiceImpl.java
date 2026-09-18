package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1ColumnOptionFact;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.IamS1SnapshotOption;
import com.dataocean.module.permission.s1.entity.IamS1TableOptionFact;
import com.dataocean.module.permission.s1.entity.vo.IamS1ColumnOptionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1TableOptionVO;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1ResourceOptionMapper;
import com.dataocean.module.permission.s1.service.IamS1ResourceOptionService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.permission.s1.support.IamS1Labels;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** IAM-SIMPLE-1 资源选项实现。读取前按负责源强制校验。 */
@Service
@RequiredArgsConstructor
public class IamS1ResourceOptionServiceImpl implements IamS1ResourceOptionService {

    private final IamS1ResourceOptionMapper resourceOptionMapper;
    private final IamS1FieldProtectionMapper fieldProtectionMapper;
    private final IamS1AdminGuard adminGuard;

    @Override
    public List<IamS1SnapshotOption> publishedSnapshots(Long operatorUserId, Long datasourceId) {
        adminGuard.requireDatasourceFunction(operatorUserId, "security:permission:view", datasourceId);
        return resourceOptionMapper.selectPublishedSnapshots(datasourceId);
    }

    @Override
    public List<IamS1TableOptionVO> tables(Long operatorUserId, Long datasourceId, Long snapshotId) {
        adminGuard.requireDatasourceFunction(operatorUserId, "security:permission:view", datasourceId);
        if (snapshotId == null) {
            throw new BusinessException("请先选择已发布元数据快照");
        }
        if (resourceOptionMapper.countPublishedSnapshot(datasourceId, snapshotId) != 1) {
            throw new BusinessException("元数据快照不存在或未发布");
        }
        List<IamS1TableOptionVO> options = new ArrayList<>();
        for (IamS1TableOptionFact table : resourceOptionMapper.selectTables(datasourceId, snapshotId)) {
            String status = table.getGovernanceStatus();
            boolean selectable = status != null && !"BLOCKED".equals(status) && !"DEPRECATED".equals(status);
            int columnCount = resourceOptionMapper.selectColumns(datasourceId, snapshotId, table.getTableName()).size();
            options.add(new IamS1TableOptionVO(datasourceId, snapshotId, table.getTableName(),
                    table.getTableComment(), status, selectable, columnCount));
        }
        return options;
    }

    @Override
    public List<IamS1ColumnOptionVO> columns(Long operatorUserId, Long datasourceId, Long snapshotId,
                                             String tableName) {
        adminGuard.requireDatasourceFunction(operatorUserId, "security:permission:view", datasourceId);
        if (snapshotId == null || tableName == null || tableName.isBlank()) {
            throw new BusinessException("请先选择快照和表");
        }
        Map<Long, String> protectionLevels = activeProtectionLevels(datasourceId, snapshotId);
        List<IamS1ColumnOptionVO> options = new ArrayList<>();
        for (IamS1ColumnOptionFact column : resourceOptionMapper.selectColumns(datasourceId, snapshotId,
                tableName.trim())) {
            String governance = column.getGovernanceStatus();
            String protectionLevel = protectionLevels.getOrDefault(column.getId(),
                    IamS1Constants.PROTECTION_NORMAL);
            boolean selectable = governance != null && !"BLOCKED".equals(governance)
                    && !"DEPRECATED".equals(governance)
                    && !IamS1Constants.PROTECTION_HIDDEN.equals(protectionLevel);
            options.add(new IamS1ColumnOptionVO(column.getId(), column.getColumnName(),
                    column.getColumnComment(), column.getDataType(), governance, protectionLevel, selectable));
        }
        return options;
    }

    private Map<Long, String> activeProtectionLevels(Long datasourceId, Long snapshotId) {
        Map<Long, String> levels = new LinkedHashMap<>();
        List<IamS1FieldProtection> protections = fieldProtectionMapper.selectActiveBySnapshot(
                IamS1Constants.PROTOCOL_VERSION, datasourceId, snapshotId);
        if (protections == null) {
            return levels;
        }
        for (IamS1FieldProtection protection : protections) {
            if (protection.getColumnMetaId() != null) {
                // 敏感分类与保护策略冲突时采用更严格的状态，页面与后端使用同一判定。
                levels.merge(protection.getColumnMetaId(), protection.getProtectionLevel(),
                        (left, right) -> stricter(left, right));
            }
        }
        return levels;
    }

    private String stricter(String left, String right) {
        List<String> order = List.of(IamS1Constants.PROTECTION_NORMAL, IamS1Constants.PROTECTION_MASKED,
                IamS1Constants.PROTECTION_HIDDEN);
        int leftIndex = order.indexOf(left);
        int rightIndex = order.indexOf(right);
        return leftIndex >= rightIndex ? left : right;
    }

    /** 供列表页展示的中文名：优先字段注释，其次物理名。 */
    public static String displayName(String columnComment, String columnName) {
        return columnComment == null || columnComment.isBlank() ? columnName : columnComment;
    }

    public static String protectionLabel(String level) {
        return IamS1Labels.protectionLevelName(level);
    }
}
