package com.dataocean.module.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceAccess;
import com.dataocean.module.datasource.entity.req.DatasourceAccessGrantRequest;
import com.dataocean.module.datasource.entity.vo.DatasourceAccessVO;
import com.dataocean.module.datasource.entity.vo.DatasourceSimpleVO;
import com.dataocean.module.datasource.mapper.DatasourceAccessMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.service.DatasourceAccessService;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatasourceAccessServiceImpl implements DatasourceAccessService {

    private final DatasourceMapper datasourceMapper;
    private final DatasourceAccessMapper accessMapper;
    private final UserMapper userMapper;

    @Transactional
    @Override
    public int grantAccess(Long datasourceId, DatasourceAccessGrantRequest request, Long grantedBy) {
        requireDatasource(datasourceId);
        validateUsers(request.getUserIds());
        int granted = 0;
        for (Long userId : Set.copyOf(request.getUserIds())) {
            DatasourceAccess access = accessMapper.selectOne(new LambdaQueryWrapper<DatasourceAccess>()
                    .eq(DatasourceAccess::getDatasourceId, datasourceId)
                    .eq(DatasourceAccess::getUserId, userId));
            if (access == null) {
                access = new DatasourceAccess();
                access.setDatasourceId(datasourceId);
                access.setUserId(userId);
                access.setGrantedBy(grantedBy);
                access.setExpiresAt(request.getExpiresAt());
                try {
                    accessMapper.insert(access);
                    granted++;
                } catch (DuplicateKeyException ignored) {
                    updateExisting(datasourceId, userId, request.getExpiresAt(), grantedBy);
                }
            } else {
                access.setExpiresAt(request.getExpiresAt());
                access.setGrantedBy(grantedBy);
                accessMapper.updateById(access);
                granted++;
            }
        }
        log.info("数据源授权完成 datasourceId={} granted={}", datasourceId, granted);
        return granted;
    }

    @Transactional
    @Override
    public void revokeAccess(Long datasourceId, Long userId) {
        requireDatasource(datasourceId);
        accessMapper.delete(new LambdaQueryWrapper<DatasourceAccess>()
                .eq(DatasourceAccess::getDatasourceId, datasourceId)
                .eq(DatasourceAccess::getUserId, userId));
        log.info("数据源授权已撤销 datasourceId={} userId={}", datasourceId, userId);
    }

    @Override
    public List<DatasourceAccessVO> listAccess(Long datasourceId) {
        requireDatasource(datasourceId);
        return accessMapper.selectAccessList(datasourceId);
    }

    @Override
    public List<DatasourceSimpleVO> listAccessibleDatasources(Long userId) {
        return datasourceMapper.selectAccessibleByUserId(userId);
    }

    @Override
    public boolean checkAccess(Long datasourceId, Long userId) {
        Long count = accessMapper.countEnabledAccess(datasourceId, userId);
        return count > 0;
    }

    private void updateExisting(Long datasourceId, Long userId, LocalDateTime expiresAt, Long grantedBy) {
        DatasourceAccess existing = accessMapper.selectOne(new LambdaQueryWrapper<DatasourceAccess>()
                .eq(DatasourceAccess::getDatasourceId, datasourceId)
                .eq(DatasourceAccess::getUserId, userId));
        if (existing != null) {
            existing.setExpiresAt(expiresAt);
            existing.setGrantedBy(grantedBy);
            accessMapper.updateById(existing);
        }
    }

    private void requireDatasource(Long datasourceId) {
        Datasource datasource = datasourceMapper.selectOne(new LambdaQueryWrapper<Datasource>()
                .eq(Datasource::getId, datasourceId)
                .eq(Datasource::getDeleted, 0L));
        if (datasource == null) {
            throw new BusinessException("数据源不存在");
        }
    }

    private void validateUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            throw new BusinessException("授权用户不能为空");
        }
        List<SysUser> users = userMapper.selectByIds(userIds);
        if (users.size() != Set.copyOf(userIds).size()) {
            throw new BusinessException("存在不存在的授权用户");
        }
    }
}
