package com.dataocean.module.datasource.service;

import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.entity.po.CollectionTaskPO;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.mapper.CatalogMapper;
import com.dataocean.module.datasource.mapper.CollectionMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper.RevisionRow;
import com.dataocean.module.datasource.source.SourceConnection;
import com.dataocean.module.datasource.source.SourceConnection.Type;
import com.dataocean.module.datasource.source.SourceJdbc;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceMetadata.Session;
import com.dataocean.module.datasource.source.SourceMetadata.Structure;
import com.dataocean.module.datasource.source.SourceProbe;
import com.dataocean.module.datasource.source.SourceSecretCodec;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.PermissionService;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 执行持久化的待采集任务。只有独立源连接确认全部采集会话结束后才释放占位。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionRunner {

    /**
     * 源当前占位、固定配置和凭据。
     */
    private final DatasourceMapper sources;

    /**
     * 平台持久化任务和停止证据。
     */
    private final CollectionMapper tasks;

    /**
     * 读取进度候选保存位置。
     */
    private final CatalogMapper catalog;

    /**
     * 将最终可靠结果原子发布为目录。
     */
    private final CatalogPublication publication;

    /**
     * 与分项测试相同的源库 JDBC 读取能力。
     */
    private final SourceJdbc jdbc;

    /**
     * 将受控 SQLState 转换为用户可理解的结果。
     */
    private final SourceProbe probe;

    /**
     * 密文凭据与内部会话证据。
     */
    private final SourceSecretCodec cipher;

    /**
     * 不透明的来源身份摘要。
     */
    private final SecretKeyProvider signatures;

    /**
     * 类型化证据编码。
     */
    private final JsonCodec json;

    /**
     * 平台任务变更采用短事务，源库网络查询放在事务外。
     */
    private final TransactionTemplate transaction;

    /**
     * 与 IAM 并发授予和收回使用同一权威访问边界。
     */
    private final GuardMapper guard;

    /**
     * 读取保存任务对应人员的当前在职／启用状态。
     */
    private final UserMapper users;

    /**
     * 异步阶段使用真实任务主体的当前权限。
     */
    private final PermissionService permissions;

    /**
     * 系统实际完成操作的审计。
     */
    private final AuditService audit;

    /**
     * UTC 系统时钟。
     */
    private final Clock clock;

    /**
     * 当前服务实例的工作者标识。
     */
    private final String owner = UUID.randomUUID().toString();

    /**
     * 独立线程负责等待源库，避免占据公共定时器。
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * 只保留一个本地调度工作，库内行锁继续防止跨进程重复。
     */
    private final AtomicBoolean busy = new AtomicBoolean();

    /**
     * 周期性领取真实待办及恢复需要重新核实的执行。
     */
    @Scheduled(fixedDelayString = "${dataocean.datasource.worker-delay-ms:1000}")
    public void tick() {
        if (busy.compareAndSet(false, true)) {
            // 源库网络等待交给独立工作线程。
            executor.submit(() -> {
                try {
                    // 优先确认既有执行，防止被新任务无限延后。
                    tasks.recoverable().forEach(this::recover);
                    tasks.queued().forEach(this::run);
                } catch (RuntimeException error) {
                    // 泄露源地址的底层 SQL 错误仅保留异常类型及最后存储状态。
                    log.error(
                            "数据源采集调度异常，后续将按持久化状态恢复，errorType={}",
                            error.getClass().getSimpleName());
                } finally {
                    // 下一次调度可重新尝试未完成任务。
                    busy.set(false);
                }
            });
        }
    }

    /**
     * 停止本地工作线程，未结束的任务保留可恢复租约。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 领取并运行原任务，所有源侧操作关联登记的代次。
     */
    private void run(String id) {
        CollectionTaskPO known = tasks.task(id, false);
        if (known == null) {
            return;
        }
        CollectionTaskPO task = transaction.execute(status -> {
            // 权限系统先于来源与任务加锁。
            guard.shared();
            DatasourcePO source = sources.lock(known.getSourceId());
            CollectionTaskPO current = tasks.task(id, true);
            if (!"QUEUED".equals(current.getState()) || !Objects.equals(source.getActiveTaskId(), id)) {
                return null;
            }
            if (current.getQueueDeadlineAt().isBefore(now())
                    || !allowed(current)
                    || !"ENABLED".equals(source.getLifecycle())) {
                terminal(current, "FAILED", "COLLECTION_NOT_ADMITTED", "等待超时或当前采集资格已失效");
                return null;
            }
            RevisionRow revision = sources.revision(source.getId(), current.getConfigRevisionId());
            tasks.claim(current, owner, revision.limits().getTaskTimeoutSeconds());
            return tasks.task(id, false);
        });
        if (task == null) {
            return;
        }

        RevisionRow revision = sources.revision(task.getSourceId(), task.getConfigRevisionId());
        SourceConnection target = target(revision);
        String errorCode = null;
        String errorMessage = null;
        try {
            // 密码仅在源库读取阶段存在于进程内。
            String password = password(task);
            readWithRetry(task, target, password, revision.limits().getRetryLimit());
        } catch (SQLException error) {
            // 已经开始的源连接统一进入独立的结束检查。
            errorCode = probe.failureCode(error);
            errorMessage = probe.failureMessage(errorCode);
        } catch (WorkEnded error) {
            // 取消、预算耗尽或租约被废止均禁止提交候选。
            errorCode = "TASK_BUDGET_EXCEEDED";
            errorMessage = "采集请求已经停止或到达执行时限";
        } catch (RuntimeException error) {
            // 平台事务失败留待下次按存储状态核实；诊断只保存必要关联信息。
            log.error(
                    "数据源采集工作异常，taskId={},errorType={}",
                    task.getId(),
                    error.getClass().getSimpleName());
            errorCode = "COLLECTION_FAILED";
            errorMessage = "平台未能完成采集处理";
        }

        finish(task, target, errorCode, errorMessage);
    }

    /**
     * 在尚无候选对象读入时重试瞬时连接故障，前一来源连接必须已有停止证据。
     */
    private void readWithRetry(CollectionTaskPO task, SourceConnection target, String password, int retryLimit)
            throws SQLException {
        for (int retries = 0; ; retries++) {
            try {
                // 每次由原任务预算、执行代次和取消状态共同控制。
                readSource(task, target, password);
                return;
            } catch (SQLException error) {
                var current = tasks.task(task.getId(), false);
                if (retries >= retryLimit
                        || !"SOURCE_NETWORK".equals(probe.failureCode(error))
                        || current.getCompleteCount() + current.getPartialCount() + current.getFailedCount() > 0
                        || !verifyStop(task, target)) {
                    // 保留已获取的候选诊断，避免混用不同连接上的对象定义。
                    throw error;
                }
                log.debug("采集连接将按原任务预算重试，taskId={},retry={}", task.getId(), retries + 1);
            }
        }
    }

    /**
     * 从已登记连接读取对象及结构，并在受控时间内复读校验快照。
     */
    private void readSource(CollectionTaskPO task, SourceConnection target, String password) throws SQLException {
        pulse(task, "CONNECTING");
        String marker = "do-read-" + UUID.randomUUID();
        try (Connection connection = jdbc.open(target, password, marker)) {
            Session session = jdbc.session(connection, target, marker);
            String execution = UUID.randomUUID().toString();
            String version = cipher.version();
            byte[] envelope = (version + ":"
                            + java.util.Base64.getEncoder()
                                    .encodeToString(cipher.encrypt(
                                            json.write(session), "datasource:execution:" + execution, version)))
                    .getBytes(StandardCharsets.US_ASCII);
            transaction.executeWithoutResult(status -> {
                // 登记之前申请源行锁，以防与取消和接管竞争。
                sources.lock(task.getSourceId());
                pulse(task, "ENUMERATING");
                tasks.register(task, execution, signatures.hmac("source-instance|" + session.instance()), envelope);
                tasks.reading(execution);
            });
            if (!jdbc.readOnlySafety(connection, target)) {
                // 已启用源仍须在采集时再次核对实际账号安全。
                throw new SQLException("采集账号已不具备安全只读条件", "42501");
            }
            List<ObjectRef> discovered = jdbc.enumerate(connection, target);
            transaction.executeWithoutResult(status -> {
                // 候选计数只在完整枚举后对外成为已知值。
                sources.lock(task.getSourceId());
                pulse(task, "READING");
                tasks.enumerated(task, discovered.size());
            });

            long snapshot = tasks.snapshot(task.getId()).getId();
            boolean incomplete = false;
            for (ObjectRef item : discovered) {
                // 每个读取步骤开始前重新检查取消、时间和执行代次。
                pulse(task, "READING");
                try {
                    Structure structure = jdbc.read(connection, target, item);
                    store(task, snapshot, item, structure, null, null);
                } catch (SQLException error) {
                    // 记录具体对象的缺项，完整目录不会使用失败候选。
                    String code = probe.failureCode(error);
                    store(task, snapshot, item, null, code, probe.failureMessage(code));
                    incomplete = true;
                }
            }
            if (incomplete) {
                // 本次失败的单对象证据仍可审计。
                throw new SQLException("必要结构未读取完整", "DO003");
            }

            pulse(task, "COMPARING");
            if (!discovered.equals(jdbc.enumerate(connection, target))) {
                // 检查可见对象清单在读取期间是否变化。
                throw new SQLException("采集过程中对象清单发生变化", "DO005");
            }
            for (var candidate : catalog.members(snapshot)) {
                // 再读一次完整结构，避免同一候选拼装不同 DDL 修订。
                pulse(task, "COMPARING");
                Structure current =
                        jdbc.read(connection, target, candidate.structure().object());
                if (!signatures.matches(candidate.definitionDigest(), publication.definition(current))) {
                    throw new SQLException("采集过程中对象结构发生变化", "DO005");
                }
            }
        }
    }

    /**
     * 持久化一次单对象读取，当前代次丧失资格时禁止写候选。
     */
    private void store(
            CollectionTaskPO task, long snapshot, ObjectRef object, Structure structure, String code, String message) {
        transaction.executeWithoutResult(status -> {
            // 与取消、完成及接管共用源锁，避免写入已弃用代次。
            sources.lock(task.getSourceId());
            pulse(task, "READING");
            catalog.saveCandidate(
                    snapshot,
                    object,
                    publication.path(object),
                    structure == null ? null : publication.definition(structure),
                    structure,
                    code,
                    message);
            tasks.attempted(task, structure != null);
        });
    }

    /**
     * 在同实例独立观察后写任务事实。仍有旧连接时只登记核实进度。
     */
    private void finish(CollectionTaskPO task, SourceConnection target, String code, String message) {
        boolean proved = verifyStop(task, target);
        transaction.executeWithoutResult(status -> {
            // 成功切换与撤权、取消都在此锁序重新核对。
            guard.shared();
            DatasourcePO source = sources.lock(task.getSourceId());
            CollectionTaskPO current = tasks.task(task.getId(), true);
            if (!"RUNNING".equals(current.getState())
                    || current.getRunVersion() != task.getRunVersion()
                    || !Objects.equals(current.getLeaseOwner(), task.getLeaseOwner())) {
                return;
            }
            if (!proved || !tasks.outstanding(current.getId()).isEmpty()) {
                tasks.uncertain(current, code == null ? "STOP_UNVERIFIED" : code, message);
                return;
            }
            if (current.getCancelRequestedAt() != null) {
                terminal(current, "CANCELLED", null, null);
            } else if (code != null || current.getExecutionDeadlineAt().isBefore(now())) {
                terminal(
                        current,
                        "FAILED",
                        code == null ? "TASK_BUDGET_EXCEEDED" : code,
                        message == null ? "采集超过执行时限" : message);
            } else if (!allowed(current)
                    || !"ENABLED".equals(source.getLifecycle())
                    || !stillApplicable(source, current)) {
                terminal(current, "FAILED", "COLLECTION_NOT_ADMITTED", "采集期间来源配置或资格已变化");
            } else {
                publication.publish(source, current);
                record(current, "SUCCEEDED");
            }
        });
    }

    /**
     * 编辑联系信息或定时参数不会否定已经固定的读取范围及原凭据。
     */
    private boolean stillApplicable(DatasourcePO source, CollectionTaskPO task) {
        if (Objects.equals(source.getCurrentConnectionRevisionId(), task.getConfigRevisionId())) {
            return true;
        }
        RevisionRow original = sources.revision(source.getId(), task.getConfigRevisionId());
        RevisionRow current = sources.revision(source.getId(), source.getCurrentConnectionRevisionId());
        return Objects.equals(original.credentialId(), current.credentialId())
                && signatures.matches(original.scopeKey(), current.scopeKey());
    }

    /**
     * 失去原工作进程或源返回不明时先冻结旧代次，再检查已登记连接。
     */
    private void recover(String id) {
        CollectionTaskPO known = tasks.task(id, false);
        if (known == null) {
            return;
        }
        CollectionTaskPO recoverable = transaction.execute(status -> {
            // 已结束的任务或尚有效工作者不能被接管。
            sources.lock(known.getSourceId());
            CollectionTaskPO current = tasks.task(id, true);
            if (!"RUNNING".equals(current.getState())) {
                return null;
            }
            if (current.getLeaseUntil().isBefore(now()) && current.getLeaseOwner() != null) {
                tasks.takeOver(current);
                return tasks.task(id, false);
            }
            return "UNKNOWN".equals(current.getExecutionState()) ? current : null;
        });
        if (recoverable != null) {
            // 恢复工作只核实停止，不重接候选读取。
            RevisionRow revision = sources.revision(recoverable.getSourceId(), recoverable.getConfigRevisionId());
            String code = recoverable.getFailureCode() == null ? "WORKER_INTERRUPTED" : recoverable.getFailureCode();
            finish(
                    recoverable,
                    target(revision),
                    code,
                    recoverable.getFailureMessage() == null ? "原采集未能确认完整结束，请重新采集" : recoverable.getFailureMessage());
        }
    }

    /**
     * 独立同账号连接确认所有旧读取的原实例和会话标记。
     */
    private boolean verifyStop(CollectionTaskPO task, SourceConnection target) {
        var outstanding = tasks.outstanding(task.getId());
        if (outstanding.isEmpty()) {
            return true;
        }
        try (Connection observer = jdbc.open(target, password(task), "do-observe-" + UUID.randomUUID())) {
            for (var execution : outstanding) {
                // 无法读取证据或原会话仍在均保留占位。
                String[] envelope = new String(execution.locator(), StandardCharsets.US_ASCII).split(":", 2);
                String value = cipher.decrypt(
                        java.util.Base64.getDecoder().decode(envelope[1]),
                        "datasource:execution:" + execution.id(),
                        envelope[0]);
                if (!jdbc.isStopped(observer, target, json.read(value, Session.class))) {
                    return false;
                }
                tasks.stopped(execution.id());
            }
            return true;
        } catch (SQLException error) {
            // 网络中断和证据不可访问都没有证明原会话停止。
            return false;
        }
    }

    /**
     * 从固定修订建立单次源库查询的统一边界。
     */
    private SourceConnection target(RevisionRow revision) {
        var config = revision.configuration();
        return new SourceConnection(
                Type.valueOf(config.getDatabaseType()),
                config.getHost(),
                config.getPort(),
                config.getDatabaseName(),
                config.getSchemas(),
                config.getAccount(),
                Math.min(20, revision.limits().getQueryTimeoutSeconds()));
    }

    /**
     * 加载任务建立时固定的原运行凭据。
     */
    private String password(CollectionTaskPO task) {
        var row = sources.credential(task.getSourceId(), task.getConfigRevisionId());
        return cipher.decrypt(row.ciphertext(), "datasource:credential:" + task.getSourceId(), row.keyVersion());
    }

    /**
     * 查阅当前身份和实际所需动作资格。
     */
    private boolean allowed(CollectionTaskPO task) {
        if (task.getActorId() == null) {
            return true;
        }
        var user = users.selectById(task.getActorId());
        if (user == null || !"ENABLED".equals(user.getLifecycle())) {
            return false;
        }
        var codes = permissions.effective(task.getActorId());
        return codes.contains("collections.view")
                && codes.contains(task.getParentTaskId() == null ? "collections.collect" : "collections.retry");
    }

    /**
     * 收到取消、换代或预算耗尽的工作不再读取源库。
     */
    private void pulse(CollectionTaskPO task, String phase) {
        if (!tasks.pulse(task, phase)) {
            throw new WorkEnded();
        }
    }

    /**
     * 写入已结束且未发布目录的真实结果。
     */
    public void terminal(CollectionTaskPO task, String state, String code, String message) {
        tasks.finishWithoutCatalog(task, state, code, message);
        record(task, state);
    }

    /**
     * 系统审计保存任务终态，原发起者保留在任务事实。
     */
    private void record(CollectionTaskPO task, String state) {
        audit.system(
                AuditWriteDTO.builder()
                        .actionCode("collections.finish")
                        .targetType("collection")
                        .targetId(task.getId())
                        .targetName(task.getTaskNo())
                        .changes(List.of(new ChangeVO("state", "任务状态", task.getState(), state)))
                        .beforeRoles(List.of())
                        .afterRoles(List.of())
                        .securityVersion(guard.selectById(1).getSecurityVersion())
                        .build(),
                "结构采集");
    }

    /**
     * 与平台库的任务时间都采用 UTC。
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * 工作代次已经无权发起下一次读取。
     */
    private static class WorkEnded extends RuntimeException {}
}
