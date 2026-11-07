package com.dataocean.module.query.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.module.query.client.IamS1PythonClient;
import com.dataocean.module.query.entity.po.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证持久意图的投递编排和失败恢复，不连接数据库或远端 Python。 */
@ExtendWith(MockitoExtension.class)
class IamS1QueryCancellationServiceImplTest {
    @Mock private QueryTaskMapper tasks;
    @Mock private IamS1PythonClient python;
    @Mock private PlatformTransactionManager transactions;

    private IamS1QueryCancellationServiceImpl service;

    @BeforeAll
    static void initTableMetadata() {
        // 仅初始化 MyBatis 字段映射，以便检查实际条件 UPDATE，不建立 SqlSession 或数据库连接。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QueryTask.class);
    }

    @BeforeEach
    void setUp() {
        // 生产使用专用有界线程池；测试用可控执行器使每个失败窗口确定发生。
        lenient().when(tasks.update(isNull(), any())).thenReturn(1);
        lenient().when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        service = coordinator(Runnable::run);
    }

    @Test
    void rollbackDoesNotEnqueueOrSendCancellation() {
        // 本地事务尚未提交时只注册回调，回滚后既不排队也不访问远端。
        List<Runnable> queued = new ArrayList<>();
        service = coordinator(queued::add);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.dispatchAfterCommit("task-1");
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            assertThat(queued).isEmpty();
            verifyNoInteractions(tasks, python);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void remoteFailureRemainsPendingAndANewCoordinatorCanRetryIt() {
        // 模拟丢失进程内状态后由持久 PENDING 接管；不把接收失败改成终态或已接收。
        doThrow(new IllegalStateException("模拟 Python 不可用")).doNothing().when(python).cancelTask("task-1");
        service.dispatchAfterCommit("task-1");
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pendingTask()));

        coordinator(Runnable::run).retryPending();

        ArgumentCaptor<LambdaUpdateWrapper<QueryTask>> changes = changes();
        verify(tasks, times(3)).update(isNull(), changes.capture());
        assertThat(changes.getAllValues().get(0).getSqlSet()).doesNotContain("cancellation_delivery_status");
        assertThat(changes.getAllValues().get(2).getParamNameValuePairs().values()).contains("ACCEPTED");
        verify(python, times(2)).cancelTask("task-1");
    }

    @Test
    void lostAcknowledgementPersistenceCanSafelyResendTheSameTask() {
        // 远端已接收但本地 ACK 落库失败时，仍按同 taskId 补投，避免把网络回执当持久完成。
        when(tasks.update(isNull(), any())).thenReturn(1)
                .thenThrow(new DataAccessResourceFailureException("模拟回执提交失败"))
                .thenReturn(1, 1);
        service.dispatchAfterCommit("task-1");
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pendingTask()));

        coordinator(Runnable::run).retryPending();

        verify(python, times(2)).cancelTask("task-1");
        verify(tasks, times(4)).update(isNull(), any());
    }

    @Test
    void executorRejectionPreservesPendingIntentForTheNextScan() {
        // 首次排队被拒绝不能执行 HTTP 或修改意图，容量恢复后下轮扫描仍能交付。
        AtomicBoolean reject = new AtomicBoolean(true);
        service = coordinator(command -> {
            if (reject.getAndSet(false)) throw new RejectedExecutionException("模拟取消线程池已满");
            command.run();
        });

        assertThatCode(() -> service.dispatchAfterCommit("task-1")).doesNotThrowAnyException();
        verifyNoInteractions(tasks, python);
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pendingTask()));
        service.retryPending();

        verify(python).cancelTask("task-1");
    }

    @Test
    void duplicateTriggersShareOneQueuedDeliveryAndAnAcceptedIntentIsNotResent() {
        // 提交后回调与定时扫描同时触发时共用一次排队；已接收后的重复触发由 CAS 拒绝。
        List<Runnable> queued = new ArrayList<>();
        service = coordinator(queued::add);
        when(tasks.update(isNull(), any())).thenReturn(1, 1, 0);
        service.dispatchAfterCommit("task-1");
        service.dispatchAfterCommit("task-1");
        assertThat(queued).hasSize(1);
        queued.remove(0).run();

        service.dispatchAfterCommit("task-1");
        queued.remove(0).run();

        verify(python).cancelTask("task-1");
    }

    @Test
    void successfulOrNonPendingTaskCannotBeClaimed() {
        // 数据库条件更新没有取得待投递行时，绝不能把成功任务当取消对象。
        when(tasks.update(isNull(), any())).thenReturn(0);

        service.dispatchAfterCommit("task-1");

        verifyNoInteractions(python);
        ArgumentCaptor<LambdaUpdateWrapper<QueryTask>> changes = changes();
        verify(tasks).update(isNull(), changes.capture());
        assertThat(changes.getValue().getSqlSegment()).contains("iam_protocol_version", "cancellation_delivery_status");
        assertThat(changes.getValue().getParamNameValuePairs().values())
                .contains("IAM-SIMPLE-1", "CANCELLED", "TIMEOUT", "PENDING").doesNotContain("COMPLETED");
    }

    @Test
    void pendingRecoveryHasNoTaskAgeExpiryAndUsesABoundedDueBatch() {
        // 很久以前的未接收意图也必须可恢复；重试时间控制频率，不以任务创建时间淘汰。
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pendingTask()));

        service.retryPending();

        ArgumentCaptor<LambdaQueryWrapper<QueryTask>> selection = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tasks).selectList(selection.capture());
        assertThat(selection.getValue().getSqlSegment()).contains("cancellation_next_attempt_at", "LIMIT 20")
                .doesNotContain("created_at");
        assertThat(selection.getValue().getParamNameValuePairs().values()).contains("PENDING");
        verify(python).cancelTask("task-1");
    }

    @Test
    void httpRunsOutsideTheTwoIndependentPersistenceTransactions() {
        // 领取与 ACK 各自用 REQUIRES_NEW 提交，HTTP 期间不持有本地事务。
        AtomicBoolean inTransaction = new AtomicBoolean();
        when(transactions.getTransaction(any())).thenAnswer(invocation -> {
            inTransaction.set(true);
            return new SimpleTransactionStatus();
        });
        doAnswer(invocation -> { inTransaction.set(false); return null; }).when(transactions).commit(any());
        doAnswer(invocation -> { assertThat(inTransaction).isFalse(); return null; }).when(python).cancelTask("task-1");

        service.dispatchAfterCommit("task-1");

        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).allSatisfy(definition ->
                assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    private IamS1QueryCancellationServiceImpl coordinator(Executor executor) {
        // 重建服务用于模拟进程内队列/去重集合丢失，数据库替身仍返回同一持久意图。
        return new IamS1QueryCancellationServiceImpl(tasks, python, transactions, executor);
    }

    private QueryTask pendingTask() {
        // 记录故意早于任何运行时限，确保恢复逻辑不会用时间窗口静默丢弃它。
        return QueryTask.builder().id(9L).taskId("task-1").iamProtocolVersion("IAM-SIMPLE-1")
                .status("CANCELLED").cancellationDeliveryStatus("PENDING")
                .createdAt(LocalDateTime.now().minusMonths(1)).build();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<QueryTask>> changes() {
        // 捕获实际生成的条件更新，只检查业务保护边界，不模拟另一套 SQL 执行器。
        return ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }
}
