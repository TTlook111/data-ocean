package com.dataocean.module.governance.service.impl;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.module.datasource.service.GovernanceSourceRows;
import com.dataocean.module.governance.config.GovernanceAiProperties;
import com.dataocean.module.governance.mapper.GovernanceSampleMapper;
import com.dataocean.module.governance.service.impl.GovernanceGenerationTasks.SampleContext;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 字段解释按实际缺口短时读取同源样本，平台只持久化执行证据与消耗。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GovernanceSamples {

    /**
     * 当前任务及来源资格。
     */
    private final GovernanceGenerationTasks tasks;

    /**
     * 固定查询和实际只读连接。
     */
    private final GovernanceSourceRows rows;

    /**
     * 当前环境允许外发的确切源字段。
     */
    private final GovernanceAiProperties properties;

    /**
     * 读取前先持久化尝试，失败不归还额度。
     */
    private final GovernanceSampleMapper attempts;

    /**
     * 将额度登记和业务源网络调用分开，避免持锁等待源端。
     */
    private final TransactionTemplate transactions;

    /**
     * 在已保存的该任务范围内取少量样本，重复轮次不会重读。
     */
    public Sample read(String requestId, int runVersion, int round, String reason) {
        String request = UUID.fromString(requestId).toString();
        if (round < 1 || round > 3 || !Set.of("MEANING", "VALUE_FORMAT").contains(reason)) {
            // 模型不能指定 SQL、列、返回量或任意诊断日志。
            throw BizIllegalException.invalid("读取轮次或用途无效");
        }
        Reservation reservation;
        try {
            // 先落实申请和预算，响应丢失时原轮次也不会重复读取。
            reservation = transactions.execute(status -> reserve(request, runVersion, round, reason));
        } catch (BizIllegalException denied) {
            // 不披露受限字段值或源授权详情。
            return new Sample("RESTRICTED", List.of(), 0);
        }
        if (reservation == null) {
            return new Sample("LIMITED", List.of(), 0);
        }
        try {
            // 源访问在平台事务之外，仅执行已生成的限定查询。
            var values = rows.read(
                    reservation.context().source(), reservation.sql(), reservation.limit(), reservation.offset());
            boolean delivered = Boolean.TRUE.equals(transactions.execute(status -> {
                // 发送前再次核权，读取期间失权、结构变动或部署策略变化时丢弃值。
                var current = tasks.sampleContext(request, runVersion);
                if (!allowed(current)
                        || !current.source()
                                .configurationId()
                                .equals(reservation.context().source().configurationId())) {
                    return false;
                }
                return attempts.finish(request, round, "SUCCEEDED", values.size()) == 1;
            }));
            if (!delivered) {
                // 回执发生正式拒绝后，实际消耗仍计入任务预算。
                attempts.finish(request, round, "DISCARDED", values.size());
                return new Sample("RESTRICTED", List.of(), 0);
            }
            return new Sample(
                    "SUCCEEDED", values, reservation.context().rowLimits().size() - round);
        } catch (SQLException failure) {
            // 源库异常只记录类型，驱动消息可能包含连接或字段内容。
            attempts.finish(request, round, "FAILED", 0);
            log.warn(
                    "治理辅助读取失败，requestId={}，round={}，errorType={}",
                    request,
                    round,
                    failure.getClass().getSimpleName());
            return new Sample(
                    "FAILED", List.of(), reservation.context().rowLimits().size() - round);
        } catch (BizIllegalException denied) {
            // 当前权限或来源已变动，停止发出读取内容。
            attempts.finish(request, round, "DISCARDED", 0);
            return new Sample("RESTRICTED", List.of(), 0);
        }
    }

    /**
     * 源锁和任务锁保证重复或并行执行不会耗用同一额度。
     */
    private Reservation reserve(String request, int runVersion, int round, String reason) {
        var context = tasks.sampleContext(request, runVersion);
        if (!allowed(context)) {
            throw BizIllegalException.denied();
        }
        var previous = attempts.attempts(request);
        if (round > context.rowLimits().size()
                || round != previous.size() + 1
                || previous.stream()
                        .anyMatch(attempt -> Set.of("RUNNING", "DISCARDED").contains(attempt.status()))) {
            // 已占用、执行不明或受限的轮次不可用新的调用绕开。
            return null;
        }
        var sql = rows.statement(
                context.source(), context.object(), context.field().name());
        var limit = context.rowLimits().get(round - 1);
        int offset = previous.stream()
                .mapToInt(GovernanceSampleMapper.Attempt::returnedRows)
                .sum();
        attempts.reserve(request, round, context.field().columnId(), reason, sql);
        return new Reservation(context, sql, limit, offset);
    }

    /**
     * 明确的部署侧许可只对当前成功目录的确切字段生效。
     */
    private boolean allowed(SampleContext context) {
        return properties.allowsSample(
                context.source().id(), context.object(), context.field().name());
    }

    /**
     * @param context 取用时的当前源和字段身份
     * @param sql 服务器生成的查询，参数按数值绑定
     * @param limit 本轮最多读取量
     * @param offset 前几轮实际返回量
     */
    private record Reservation(SampleContext context, String sql, int limit, int offset) {}

    /**
     * @param status 受控的执行或停止原因
     * @param values 仅供当次工作者使用的遮盖后短值
     * @param remaining 剩余最大尝试次数
     */
    public record Sample(String status, List<String> values, int remaining) {}
}
