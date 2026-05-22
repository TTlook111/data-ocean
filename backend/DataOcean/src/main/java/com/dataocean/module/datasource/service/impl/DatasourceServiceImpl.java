package com.dataocean.module.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceHealthCheck;
import com.dataocean.module.datasource.entity.DatasourceSecret;
import com.dataocean.module.datasource.entity.query.DatasourceQuery;
import com.dataocean.module.datasource.entity.dto.DatasourceCreateDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceTestDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceUpdateDTO;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestVO;
import com.dataocean.module.datasource.entity.vo.DatasourceVO;
import com.dataocean.module.datasource.mapper.DatasourceHealthCheckMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceSecretMapper;
import com.dataocean.module.datasource.service.DatasourceConnectionService;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.datasource.service.DatasourceService;
import com.dataocean.module.datasource.client.PythonPoolClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 数据源管理服务实现类
 * <p>
 * 实现数据源的完整生命周期管理，包括创建、更新、删除、查询、状态变更和连接测试。
 * 所有写操作均在事务中执行，创建和更新前强制进行连接测试以确保数据源可用。
 * </p>
 *
 * @author dataocean
 */
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

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public DatasourceVO createDatasource(DatasourceCreateDTO request) {
        // 校验唯一性（host + port + databaseName 不能重复）
        ensureUnique(request.getHost(), request.getPort(), request.getDatabaseName(), null);
        // 执行连接测试，确保数据源可用
        DatasourceConnectionTestVO testResult = connectionService.testConnection(
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

        // 构建数据源实体并插入
        Datasource datasource = new Datasource();
        applyCreateFields(datasource, request);
        try {
            datasourceMapper.insert(datasource);
        } catch (DuplicateKeyException exception) {
            throw duplicateException();
        }

        // 保存加密凭证
        DatasourceSecret secret = new DatasourceSecret();
        secret.setDatasourceId(datasource.getId());
        secret.setUsername(request.getUsername());
        secret.setEncryptedPassword(secretService.encrypt(request.getPassword()));
        secret.setEncryptVersion(1);
        secretMapper.insert(secret);
        // 记录手动健康检查结果
        recordManualHealth(datasource.getId(), testResult);
        log.info("数据源创建成功 datasourceId={} name={} host={} database={}",
                datasource.getId(), datasource.getName(), datasource.getHost(), datasource.getDatabaseName());
        return getDatasourceById(datasource.getId());
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public DatasourceVO updateDatasource(Long id, DatasourceUpdateDTO request) {
        // 校验数据源存在性
        Datasource datasource = requireDatasource(id);
        DatasourceSecret secret = requireSecret(id);
        // 校验唯一性（排除自身）
        ensureUnique(request.getHost(), request.getPort(), request.getDatabaseName(), id);

        // 确定测试用密码：有新密码用新密码，否则解密旧密码
        String passwordForTest = StringUtils.hasText(request.getPassword())
                ? request.getPassword()
                : secretService.decrypt(secret.getEncryptedPassword());
        // 执行连接测试
        DatasourceConnectionTestVO testResult = connectionService.testConnection(
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

        // 更新数据源基本信息
        boolean passwordChanged = StringUtils.hasText(request.getPassword());
        datasource.setName(request.getName());
        datasource.setDescription(request.getDescription());
        datasource.setHost(request.getHost());
        datasource.setPort(request.getPort());
        datasource.setDatabaseName(request.getDatabaseName());
        datasource.setCharset(resolveCharset(request.getCharset()));
        datasource.setHealthStatus(Datasource.HEALTH_HEALTHY);
        datasourceMapper.updateById(datasource);

        // 更新凭证信息
        secret.setUsername(request.getUsername());
        if (passwordChanged) {
            secret.setEncryptedPassword(secretService.encrypt(request.getPassword()));
            // 密码变更时通知 Python 服务销毁旧连接池
            pythonPoolClient.destroyPool(id);
        }
        secretMapper.updateById(secret);
        // 记录手动健康检查结果
        recordManualHealth(id, testResult);
        log.info("数据源更新成功 datasourceId={} passwordChanged={}", id, passwordChanged);
        return getDatasourceById(id);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void deleteDatasource(Long id) {
        Datasource datasource = requireDatasource(id);
        // 检查是否可以删除（无已发布快照和知识库文档）
        ensureCanDelete(id);
        // 逻辑删除：将 deleted 字段设为自身 ID
        datasource.setDeleted(id);
        datasourceMapper.updateById(datasource);
        // 通知 Python 服务销毁连接池
        pythonPoolClient.destroyPool(id);
        log.info("数据源已软删除 datasourceId={}", id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DatasourceVO getDatasourceById(Long id) {
        DatasourceVO datasource = datasourceMapper.selectVOById(id);
        if (datasource == null) {
            throw new BusinessException("数据源不存在");
        }
        return datasource;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Page<DatasourceVO> listDatasources(DatasourceQuery request) {
        // 构建查询条件：未删除 + 可选的名称模糊/状态/健康状态过滤
        LambdaQueryWrapper<Datasource> wrapper = new LambdaQueryWrapper<Datasource>()
                .eq(Datasource::getDeleted, 0L)
                .like(StringUtils.hasText(request.getName()), Datasource::getName, request.getName())
                .eq(request.getStatus() != null, Datasource::getStatus, request.getStatus())
                .eq(StringUtils.hasText(request.getHealthStatus()), Datasource::getHealthStatus, request.getHealthStatus())
                .orderByDesc(Datasource::getCreatedAt);
        // 执行分页查询
        Page<Datasource> datasourcePage = datasourceMapper.selectPage(new Page<>(request.resolvedPage(), request.resolvedPageSize()), wrapper);
        // 转换为 VO 分页结果
        Page<DatasourceVO> result = new Page<>(datasourcePage.getCurrent(), datasourcePage.getSize(), datasourcePage.getTotal());
        result.setRecords(datasourcePage.getRecords().stream()
                .map(item -> datasourceMapper.selectVOById(item.getId())).toList());
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public DatasourceVO updateStatus(Long id, Integer status) {
        Datasource datasource = requireDatasource(id);
        // 校验状态值合法性
        if (!List.of(Datasource.STATUS_ENABLED, Datasource.STATUS_DISABLED).contains(status)) {
            throw new BusinessException("数据源状态不合法");
        }
        if (Integer.valueOf(Datasource.STATUS_ENABLED).equals(status)) {
            // 启用前执行连接测试，确保数据源可用
            DatasourceSecret secret = requireSecret(id);
            DatasourceConnectionTestVO result = connectionService.testConnection(
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
            // 禁用时通知 Python 服务销毁连接池
            pythonPoolClient.destroyPool(id);
        }
        datasource.setStatus(status);
        datasourceMapper.updateById(datasource);
        log.info("数据源状态更新成功 datasourceId={} status={}", id, status);
        return getDatasourceById(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DatasourceConnectionTestVO testConnection(DatasourceTestDTO request) {
        return connectionService.testConnection(
                request.getHost(),
                request.getPort(),
                request.getDatabaseName(),
                request.getCharset(),
                request.getUsername(),
                request.getPassword()
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DatasourceConnectionTestVO testSavedConnection(Long id) {
        Datasource datasource = requireDatasource(id);
        DatasourceSecret secret = requireSecret(id);
        // 使用已保存的凭证执行连接测试
        DatasourceConnectionTestVO result = connectionService.testConnection(
                datasource,
                secret.getUsername(),
                secretService.decrypt(secret.getEncryptedPassword()),
                DatasourceHealthCheck.TYPE_MANUAL
        );
        // 测试成功时更新健康状态
        datasource.setHealthStatus(Boolean.TRUE.equals(result.getSuccess()) ? Datasource.HEALTH_HEALTHY : datasource.getHealthStatus());
        datasourceMapper.updateById(datasource);
        return result;
    }

    /**
     * 填充创建数据源时的默认字段值
     *
     * @param datasource 数据源实体
     * @param request    创建请求参数
     */
    private void applyCreateFields(Datasource datasource, DatasourceCreateDTO request) {
        datasource.setName(request.getName());
        datasource.setDescription(request.getDescription());
        datasource.setDbType(Datasource.DB_TYPE_MYSQL);
        datasource.setHost(request.getHost());
        datasource.setPort(request.getPort());
        datasource.setDatabaseName(request.getDatabaseName());
        datasource.setCharset(resolveCharset(request.getCharset()));
        datasource.setStatus(Datasource.STATUS_ENABLED);
        datasource.setHealthStatus(Datasource.HEALTH_HEALTHY);
        datasource.setCreatorId(UserContext.currentUserId());
        datasource.setDeleted(0L);
    }

    /**
     * 校验数据源唯一性（host + port + databaseName 组合不能重复）
     *
     * @param host         主机地址
     * @param port         端口号
     * @param databaseName 数据库名称
     * @param currentId    当前数据源 ID（更新时排除自身，创建时为 null）
     */
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

    /**
     * 检查数据源是否可以删除
     * <p>
     * 存在已发布的元数据快照或已发布/已审核的 skills.md 时禁止删除。
     * </p>
     *
     * @param datasourceId 数据源 ID
     */
    private void ensureCanDelete(Long datasourceId) {
        if (tableExists("metadata_snapshot") && datasourceMapper.countPublishedSnapshots(datasourceId) > 0) {
            throw new BusinessException(409, "数据源存在已发布的元数据快照，无法删除");
        }
        if (tableExists("knowledge_doc") && datasourceMapper.countActiveKnowledgeDocs(datasourceId) > 0) {
            throw new BusinessException(409, "数据源存在已发布或已审核的 skills.md，无法删除");
        }
    }

    /**
     * 检查指定表是否存在于当前数据库中
     *
     * @param tableName 表名
     * @return true-存在，false-不存在
     */
    private boolean tableExists(String tableName) {
        Long count = datasourceMapper.tableExists(tableName);
        return count != null && count > 0;
    }

    /**
     * 获取数据源实体，不存在时抛出业务异常
     *
     * @param id 数据源 ID
     * @return 数据源实体
     */
    private Datasource requireDatasource(Long id) {
        Datasource datasource = datasourceMapper.selectOne(new LambdaQueryWrapper<Datasource>()
                .eq(Datasource::getId, id)
                .eq(Datasource::getDeleted, 0L));
        if (datasource == null) {
            throw new BusinessException("数据源不存在");
        }
        return datasource;
    }

    /**
     * 获取数据源凭证，不存在时抛出业务异常
     *
     * @param datasourceId 数据源 ID
     * @return 数据源凭证实体
     */
    private DatasourceSecret requireSecret(Long datasourceId) {
        DatasourceSecret secret = secretMapper.selectOne(new LambdaQueryWrapper<DatasourceSecret>()
                .eq(DatasourceSecret::getDatasourceId, datasourceId));
        if (secret == null) {
            throw new BusinessException("数据源凭证不存在");
        }
        return secret;
    }

    /**
     * 记录手动健康检查结果
     *
     * @param datasourceId 数据源 ID
     * @param result       连接测试结果
     */
    private void recordManualHealth(Long datasourceId, DatasourceConnectionTestVO result) {
        DatasourceHealthCheck check = new DatasourceHealthCheck();
        check.setDatasourceId(datasourceId);
        check.setCheckType(DatasourceHealthCheck.TYPE_MANUAL);
        check.setSuccess(Boolean.TRUE.equals(result.getSuccess()) ? 1 : 0);
        check.setResponseTimeMs(result.getResponseTimeMs() == null ? null : Math.toIntExact(Math.min(result.getResponseTimeMs(), Integer.MAX_VALUE)));
        check.setServerVersion(result.getServerVersion());
        check.setErrorMessage(Boolean.TRUE.equals(result.getSuccess()) ? null : result.getMessage());
        healthCheckMapper.insert(check);
    }

    /**
     * 解析字符集，为空时默认使用 utf8mb4
     *
     * @param charset 输入字符集
     * @return 解析后的字符集
     */
    private String resolveCharset(String charset) {
        return StringUtils.hasText(charset) ? charset : "utf8mb4";
    }

    /**
     * 构建数据源重复异常
     *
     * @return 业务异常实例
     */
    private BusinessException duplicateException() {
        return new BusinessException(409, "相同 host、port、databaseName 的数据源已存在");
    }
}
