package com.dataocean.module.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceHealthCheck;
import com.dataocean.module.datasource.entity.DatasourceSecret;
import com.dataocean.module.datasource.entity.query.DatasourceQueryRequest;
import com.dataocean.module.datasource.entity.req.DatasourceCreateRequest;
import com.dataocean.module.datasource.entity.req.DatasourceTestRequest;
import com.dataocean.module.datasource.entity.req.DatasourceUpdateRequest;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestResult;
import com.dataocean.module.datasource.entity.vo.DatasourceVO;
import com.dataocean.module.datasource.mapper.DatasourceHealthCheckMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceSecretMapper;
import com.dataocean.module.datasource.service.DatasourceConnectionService;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.datasource.service.DatasourceService;
import com.dataocean.module.datasource.service.PythonPoolClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatasourceServiceImpl implements DatasourceService {

    private final DatasourceMapper datasourceMapper;
    private final DatasourceSecretMapper secretMapper;
    private final DatasourceHealthCheckMapper healthCheckMapper;
    private final DatasourceSecretService secretService;
    private final DatasourceConnectionService connectionService;
    private final PythonPoolClient pythonPoolClient;

    @Transactional
    @Override
    public DatasourceVO createDatasource(DatasourceCreateRequest request, Long creatorId) {
        ensureUnique(request.getHost(), request.getPort(), request.getDatabaseName(), null);
        DatasourceConnectionTestResult testResult = connectionService.testConnection(
                request.getHost(),
                request.getPort(),
                request.getDatabaseName(),
                request.getCharset(),
                request.getUsername(),
                request.getPassword()
        );
        if (!Boolean.TRUE.equals(testResult.getSuccess())) {
            throw new BusinessException("数据源连接失败：" + testResult.getMessage());
        }

        Datasource datasource = new Datasource();
        applyCreateFields(datasource, request, creatorId);
        try {
            datasourceMapper.insert(datasource);
        } catch (DuplicateKeyException exception) {
            throw duplicateException();
        }

        DatasourceSecret secret = new DatasourceSecret();
        secret.setDatasourceId(datasource.getId());
        secret.setUsername(request.getUsername());
        secret.setEncryptedPassword(secretService.encrypt(request.getPassword()));
        secret.setEncryptVersion(1);
        secretMapper.insert(secret);
        recordManualHealth(datasource.getId(), testResult);
        log.info("数据源创建成功 datasourceId={} name={} host={} database={}",
                datasource.getId(), datasource.getName(), datasource.getHost(), datasource.getDatabaseName());
        return getDatasourceById(datasource.getId());
    }

    @Transactional
    @Override
    public DatasourceVO updateDatasource(Long id, DatasourceUpdateRequest request) {
        Datasource datasource = requireDatasource(id);
        DatasourceSecret secret = requireSecret(id);
        ensureUnique(request.getHost(), request.getPort(), request.getDatabaseName(), id);

        String passwordForTest = StringUtils.hasText(request.getPassword())
                ? request.getPassword()
                : secretService.decrypt(secret.getEncryptedPassword());
        DatasourceConnectionTestResult testResult = connectionService.testConnection(
                request.getHost(),
                request.getPort(),
                request.getDatabaseName(),
                request.getCharset(),
                request.getUsername(),
                passwordForTest
        );
        if (!Boolean.TRUE.equals(testResult.getSuccess())) {
            throw new BusinessException("数据源连接失败：" + testResult.getMessage());
        }

        boolean passwordChanged = StringUtils.hasText(request.getPassword());
        datasource.setName(request.getName());
        datasource.setDescription(request.getDescription());
        datasource.setHost(request.getHost());
        datasource.setPort(request.getPort());
        datasource.setDatabaseName(request.getDatabaseName());
        datasource.setCharset(resolveCharset(request.getCharset()));
        datasource.setHealthStatus(Datasource.HEALTH_HEALTHY);
        datasourceMapper.updateById(datasource);

        secret.setUsername(request.getUsername());
        if (passwordChanged) {
            secret.setEncryptedPassword(secretService.encrypt(request.getPassword()));
            pythonPoolClient.destroyPool(id);
        }
        secretMapper.updateById(secret);
        recordManualHealth(id, testResult);
        log.info("数据源更新成功 datasourceId={} passwordChanged={}", id, passwordChanged);
        return getDatasourceById(id);
    }

    @Transactional
    @Override
    public void deleteDatasource(Long id) {
        Datasource datasource = requireDatasource(id);
        ensureCanDelete(id);
        datasource.setDeleted(id);
        datasourceMapper.updateById(datasource);
        pythonPoolClient.destroyPool(id);
        log.info("数据源已软删除 datasourceId={}", id);
    }

    @Override
    public DatasourceVO getDatasourceById(Long id) {
        DatasourceVO datasource = datasourceMapper.selectVOById(id);
        if (datasource == null) {
            throw new BusinessException("数据源不存在");
        }
        return datasource;
    }

    @Override
    public Page<DatasourceVO> listDatasources(DatasourceQueryRequest request) {
        LambdaQueryWrapper<Datasource> wrapper = new LambdaQueryWrapper<Datasource>()
                .eq(Datasource::getDeleted, 0L)
                .like(StringUtils.hasText(request.getName()), Datasource::getName, request.getName())
                .eq(request.getStatus() != null, Datasource::getStatus, request.getStatus())
                .eq(StringUtils.hasText(request.getHealthStatus()), Datasource::getHealthStatus, request.getHealthStatus())
                .orderByDesc(Datasource::getCreatedAt);
        Page<Datasource> datasourcePage = datasourceMapper.selectPage(new Page<>(request.resolvedPage(), request.resolvedPageSize()), wrapper);
        Page<DatasourceVO> result = new Page<>(datasourcePage.getCurrent(), datasourcePage.getSize(), datasourcePage.getTotal());
        result.setRecords(datasourcePage.getRecords().stream().map(item -> datasourceMapper.selectVOById(item.getId())).toList());
        return result;
    }

    @Transactional
    @Override
    public DatasourceVO updateStatus(Long id, Integer status) {
        Datasource datasource = requireDatasource(id);
        if (!List.of(Datasource.STATUS_ENABLED, Datasource.STATUS_DISABLED).contains(status)) {
            throw new BusinessException("数据源状态不合法");
        }
        if (Integer.valueOf(Datasource.STATUS_ENABLED).equals(status)) {
            DatasourceSecret secret = requireSecret(id);
            DatasourceConnectionTestResult result = connectionService.testConnection(
                    datasource,
                    secret.getUsername(),
                    secretService.decrypt(secret.getEncryptedPassword()),
                    DatasourceHealthCheck.TYPE_MANUAL
            );
            if (!Boolean.TRUE.equals(result.getSuccess())) {
                throw new BusinessException("启用前连接测试失败：" + result.getMessage());
            }
            datasource.setHealthStatus(Datasource.HEALTH_HEALTHY);
        } else {
            pythonPoolClient.destroyPool(id);
        }
        datasource.setStatus(status);
        datasourceMapper.updateById(datasource);
        log.info("数据源状态更新成功 datasourceId={} status={}", id, status);
        return getDatasourceById(id);
    }

    @Override
    public DatasourceConnectionTestResult testConnection(DatasourceTestRequest request) {
        return connectionService.testConnection(
                request.getHost(),
                request.getPort(),
                request.getDatabaseName(),
                request.getCharset(),
                request.getUsername(),
                request.getPassword()
        );
    }

    @Override
    public DatasourceConnectionTestResult testSavedConnection(Long id) {
        Datasource datasource = requireDatasource(id);
        DatasourceSecret secret = requireSecret(id);
        DatasourceConnectionTestResult result = connectionService.testConnection(
                datasource,
                secret.getUsername(),
                secretService.decrypt(secret.getEncryptedPassword()),
                DatasourceHealthCheck.TYPE_MANUAL
        );
        datasource.setHealthStatus(Boolean.TRUE.equals(result.getSuccess()) ? Datasource.HEALTH_HEALTHY : datasource.getHealthStatus());
        datasourceMapper.updateById(datasource);
        return result;
    }

    private void applyCreateFields(Datasource datasource, DatasourceCreateRequest request, Long creatorId) {
        datasource.setName(request.getName());
        datasource.setDescription(request.getDescription());
        datasource.setDbType(Datasource.DB_TYPE_MYSQL);
        datasource.setHost(request.getHost());
        datasource.setPort(request.getPort());
        datasource.setDatabaseName(request.getDatabaseName());
        datasource.setCharset(resolveCharset(request.getCharset()));
        datasource.setStatus(Datasource.STATUS_ENABLED);
        datasource.setHealthStatus(Datasource.HEALTH_HEALTHY);
        datasource.setCreatorId(creatorId);
        datasource.setDeleted(0L);
    }

    private void ensureUnique(String host, Integer port, String databaseName, Long currentId) {
        LambdaQueryWrapper<Datasource> wrapper = new LambdaQueryWrapper<Datasource>()
                .eq(Datasource::getHost, host)
                .eq(Datasource::getPort, port)
                .eq(Datasource::getDatabaseName, databaseName)
                .eq(Datasource::getDeleted, 0L)
                .ne(currentId != null, Datasource::getId, currentId);
        if (datasourceMapper.selectCount(wrapper) > 0) {
            throw duplicateException();
        }
    }

    private void ensureCanDelete(Long datasourceId) {
        if (tableExists("metadata_snapshot") && datasourceMapper.countPublishedSnapshots(datasourceId) > 0) {
            throw new BusinessException(409, "数据源存在已发布的元数据快照，无法删除");
        }
        if (tableExists("knowledge_doc") && datasourceMapper.countActiveKnowledgeDocs(datasourceId) > 0) {
            throw new BusinessException(409, "数据源存在已发布或已审核的 skills.md，无法删除");
        }
    }

    private boolean tableExists(String tableName) {
        Long count = datasourceMapper.tableExists(tableName);
        return count != null && count > 0;
    }

    private Datasource requireDatasource(Long id) {
        Datasource datasource = datasourceMapper.selectOne(new LambdaQueryWrapper<Datasource>()
                .eq(Datasource::getId, id)
                .eq(Datasource::getDeleted, 0L));
        if (datasource == null) {
            throw new BusinessException("数据源不存在");
        }
        return datasource;
    }

    private DatasourceSecret requireSecret(Long datasourceId) {
        DatasourceSecret secret = secretMapper.selectOne(new LambdaQueryWrapper<DatasourceSecret>()
                .eq(DatasourceSecret::getDatasourceId, datasourceId));
        if (secret == null) {
            throw new BusinessException("数据源凭证不存在");
        }
        return secret;
    }

    private void recordManualHealth(Long datasourceId, DatasourceConnectionTestResult result) {
        DatasourceHealthCheck check = new DatasourceHealthCheck();
        check.setDatasourceId(datasourceId);
        check.setCheckType(DatasourceHealthCheck.TYPE_MANUAL);
        check.setSuccess(Boolean.TRUE.equals(result.getSuccess()) ? 1 : 0);
        check.setResponseTimeMs(result.getResponseTimeMs() == null ? null : Math.toIntExact(Math.min(result.getResponseTimeMs(), Integer.MAX_VALUE)));
        check.setServerVersion(result.getServerVersion());
        check.setErrorMessage(Boolean.TRUE.equals(result.getSuccess()) ? null : result.getMessage());
        healthCheckMapper.insert(check);
    }

    private String resolveCharset(String charset) {
        return StringUtils.hasText(charset) ? charset : "utf8mb4";
    }

    private BusinessException duplicateException() {
        return new BusinessException(409, "相同 host、port、databaseName 的数据源已存在");
    }
}
