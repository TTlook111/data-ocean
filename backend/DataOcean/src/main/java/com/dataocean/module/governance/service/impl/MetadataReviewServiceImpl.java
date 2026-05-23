package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.governance.entity.MetadataReviewRecord;
import com.dataocean.module.governance.entity.vo.ReviewRecordVO;
import com.dataocean.module.governance.mapper.MetadataReviewRecordMapper;
import com.dataocean.module.governance.service.MetadataReviewService;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 元数据治理审核记录服务实现。
 * <p>
 * 查询审核记录时批量补齐操作人姓名，供管理端展示。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class MetadataReviewServiceImpl implements MetadataReviewService {

    private final MetadataReviewRecordMapper reviewRecordMapper;
    private final UserMapper userMapper;

    /**
     * {@inheritDoc}
     */
    @Transactional(readOnly = true)
    @Override
    public Page<ReviewRecordVO> listRecords(Long snapshotId, String tableName, int page, int size) {
        LambdaQueryWrapper<MetadataReviewRecord> qw = new LambdaQueryWrapper<MetadataReviewRecord>()
                .eq(MetadataReviewRecord::getSnapshotId, snapshotId)
                .eq(StringUtils.hasText(tableName), MetadataReviewRecord::getTableName, tableName)
                .orderByDesc(MetadataReviewRecord::getCreatedAt);

        Page<MetadataReviewRecord> recordPage = reviewRecordMapper.selectPage(new Page<>(page, size), qw);

        // 批量查询操作人姓名
        Set<Long> operatorIds = recordPage.getRecords().stream()
                .map(MetadataReviewRecord::getOperatorId)
                .collect(Collectors.toSet());
        Map<Long, String> nameMap = new HashMap<>();
        if (!operatorIds.isEmpty()) {
            List<SysUser> users = userMapper.selectByIds(operatorIds);
            for (SysUser u : users) {
                nameMap.put(u.getId(), u.getRealName());
            }
        }

        Page<ReviewRecordVO> voPage = new Page<>(recordPage.getCurrent(), recordPage.getSize(), recordPage.getTotal());
        voPage.setRecords(recordPage.getRecords().stream().map(record -> toVO(record, nameMap)).toList());
        return voPage;
    }

    private ReviewRecordVO toVO(MetadataReviewRecord record, Map<Long, String> nameMap) {
        ReviewRecordVO vo = new ReviewRecordVO();
        vo.setId(record.getId());
        vo.setTargetType(record.getTargetType());
        vo.setTableName(record.getTableName());
        vo.setColumnName(record.getColumnName());
        vo.setAction(record.getAction());
        vo.setOldStatus(record.getOldStatus());
        vo.setNewStatus(record.getNewStatus());
        vo.setOperatorName(nameMap.getOrDefault(record.getOperatorId(), "未知"));
        vo.setRemark(record.getRemark());
        vo.setCreatedAt(record.getCreatedAt());
        return vo;
    }
}
