package com.dataocean.module.datasource.service;

import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO.Actor;
import com.dataocean.module.datasource.entity.vo.SourceDetailVO.Schedule;
import com.dataocean.module.datasource.mapper.CollectionScheduleMapper.ScheduleRow;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper.RevisionRow;
import com.dataocean.module.datasource.mapper.SourceStateMapper;
import com.dataocean.module.datasource.service.impl.CollectionServiceImpl;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.mapper.GuardMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 按实际到期周期、业务时区和允许窗口接收同一条采集流程。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DatasourceScheduler {

    /**
     * 同一数据源占位和固定配置。
     */
    private final DatasourceMapper sources;

    /**
     * 待调度游标和当前计划。
     */
    private final SourceStateMapper schedules;

    /**
     * 定时和人工复用相同的任务接收与快照初始化。
     */
    private final CollectionServiceImpl collection;

    /**
     * 定时接收、游标和审计同事务提交。
     */
    private final TransactionTemplate transactions;

    /**
     * 平台业务时区和源连接策略。
     */
    private final com.dataocean.module.datasource.source.SourceConnectionProperties settings;

    /**
     * 保存已接受系统任务及陈旧提醒。
     */
    private final DatasourceEvents events;

    /**
     * 系统主体记录定时接收事实。
     */
    private final AuditService audit;

    /**
     * 审计写入使用当前安全版本。
     */
    private final GuardMapper guard;

    /**
     * 可用于验证窗口边界的 UTC 时钟。
     */
    private final Clock clock;

    /**
     * 启用定时扫描。尚无首次成功的源不在计划表中。
     */
    @Scheduled(fixedDelayString = "${dataocean.datasource.schedule-delay-ms:1500}")
    public void scan() {
        for (long id : schedules.due()) {
            try {
                // 每源独立提交，单一源异常不影响其他源。
                transactions.executeWithoutResult(status -> admit(id));
            } catch (RuntimeException error) {
                log.error(
                        "检查数据源定时计划失败，sourceId={},errorType={}",
                        id,
                        error.getClass().getSimpleName());
            }
        }
    }

    /**
     * 从受控平台时钟推算下一次检查或接收最新到期项。
     */
    private void admit(long sourceId) {
        guard.shared();
        DatasourcePO source = sources.lock(sourceId);
        if (!"ENABLED".equals(source.getLifecycle())) {
            return;
        }
        ScheduleRow plan = schedules.schedule(sourceId);
        if (plan.anchor() == null) {
            return;
        }

        RevisionRow revision = sources.revision(sourceId, source.getCurrentConnectionRevisionId());
        Instant now = clock.instant();
        Duration period = Duration.ofHours(revision.limits().getSyncHours());
        Instant horizon = instant(plan.freshnessFrom());
        if (horizon != null
                && !now.isBefore(horizon.plus(period.multipliedBy(2)))
                && schedules.stale(sourceId, revision.scopeKey())) {
            // 同一段陈旧时间只发一个可靠告警，使用来源锁内已读取的目录版本。
            events.stale(sourceId, source.getCatalogVersion(), now);
        }

        Instant anchor = instant(plan.anchor());
        long cycle = Math.max(0, Duration.between(anchor, now).toSeconds() / period.toSeconds());
        long last = plan.lastCycle();
        Instant retry = now.plusSeconds(30);
        if (cycle <= last) {
            retry = anchor.plus(period.multipliedBy(last + 1));
        } else if (isEligible(source, plan, revision, now)) {
            Instant due = anchor.plus(period.multipliedBy(cycle));
            String seed = "datasource-schedule:" + sourceId + ":" + plan.revision() + ":" + cycle;
            String command = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8))
                    .toString();
            String task = collection.accept(
                    source,
                    revision,
                    command,
                    new Actor("SYSTEM", null, null, "系统调度"),
                    null,
                    plan.revision(),
                    cycle,
                    utc(due),
                    zone().getId());
            audit.system(
                    AuditWriteDTO.builder()
                            .actionCode("collections.schedule")
                            .targetType("datasource")
                            .targetId(Long.toString(sourceId))
                            .targetName(source.getName())
                            .changes(List.of(new ChangeVO("task", "采集任务", null, task)))
                            .beforeRoles(List.of())
                            .afterRoles(List.of())
                            .securityVersion(guard.selectById(1).getSecurityVersion())
                            .commandId(command)
                            .build(),
                    "结构采集调度");
            last = cycle;
            retry = anchor.plus(period.multipliedBy(last + 1));
        } else if (!inWindow(revision, now) && !plan.paused()) {
            retry = windowStart(revision, now);
        }
        Instant staleDeadline = horizon == null ? null : horizon.plus(period.multipliedBy(2));
        if (staleDeadline != null && staleDeadline.isAfter(now) && staleDeadline.isBefore(retry)) {
            // 陈旧告警不必等到下一允许执行的窗口。
            retry = staleDeadline;
        }
        schedules.cursor(sourceId, last, utc(retry));
    }

    /**
     * 表达计划当前状态并保留实际下次可调度时间。
     */
    public Schedule status(DatasourcePO source, RevisionRow revision, ScheduleRow plan) {
        Instant next = null;
        String state;
        Instant now = clock.instant();
        if (!"ENABLED".equals(source.getLifecycle())) {
            state = "INACTIVE";
        } else if (plan.anchor() == null) {
            state = "WAITING_FIRST_SUCCESS";
        } else if (plan.paused()) {
            state = "PAUSED";
        } else if (source.getActiveTaskId() != null || source.getPendingChangeId() != null) {
            state = "BLOCKED";
        } else {
            next = instant(plan.anchor())
                    .plus(Duration.ofHours(revision.limits().getSyncHours()).multipliedBy(plan.lastCycle() + 1));
            if (next.isBefore(now)) {
                next = now;
            }
            boolean within = inWindow(revision, next);
            state = within ? "READY" : "WAITING_WINDOW";
            if (!within) {
                next = windowStart(revision, next);
            }
        }
        return new Schedule(state, plan.paused(), zone().getId(), next, instant(plan.staleSince()));
    }

    /**
     * 接收时同时检查暂停、在途任务及当前真实窗口。
     */
    private boolean isEligible(DatasourcePO source, ScheduleRow plan, RevisionRow revision, Instant now) {
        return !plan.paused()
                && source.getActiveTaskId() == null
                && source.getPendingChangeId() == null
                && inWindow(revision, now);
    }

    /**
     * 窗口始端包含、末端排除。跨午夜时首尾分别判断。
     */
    private boolean inWindow(RevisionRow revision, Instant now) {
        LocalTime time = now.atZone(zone()).toLocalTime();
        LocalTime start = LocalTime.parse(revision.limits().getWindowStart());
        LocalTime end = LocalTime.parse(revision.limits().getWindowEnd());
        return start.isBefore(end)
                ? !time.isBefore(start) && time.isBefore(end)
                : !time.isBefore(start) || time.isBefore(end);
    }

    /**
     * 通过时区规则取得下一窗口起点，周期游标防止重叠小时重复接收。
     */
    private Instant windowStart(RevisionRow revision, Instant now) {
        var local = now.atZone(zone());
        LocalTime start = LocalTime.parse(revision.limits().getWindowStart());
        var day = local.toLocalDate();
        Instant candidate = day.atTime(start).atZone(zone()).toInstant();
        return candidate.isAfter(now)
                ? candidate
                : day.plusDays(1).atTime(start).atZone(zone()).toInstant();
    }

    /**
     * 取得部署决定的业务时区。
     */
    private ZoneId zone() {
        return ZoneId.of(settings.getTimeZone());
    }

    /**
     * 读取数据库固定 UTC 时间。
     */
    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /**
     * 按平台库约定保存 UTC。
     */
    private LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
