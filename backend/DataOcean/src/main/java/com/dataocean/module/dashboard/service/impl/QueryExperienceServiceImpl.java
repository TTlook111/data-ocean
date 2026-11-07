package com.dataocean.module.dashboard.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.dashboard.entity.po.QueryExperienceCounts;
import com.dataocean.module.dashboard.entity.po.QueryExperienceVotes;
import com.dataocean.module.dashboard.entity.query.QueryExperienceDetailsQuery;
import com.dataocean.module.dashboard.entity.query.QueryExperienceScopeQuery;
import com.dataocean.module.dashboard.entity.vo.QueryExperienceDetailVO;
import com.dataocean.module.dashboard.entity.vo.QueryExperienceVO;
import com.dataocean.module.dashboard.mapper.QueryExperienceMapper;
import com.dataocean.module.dashboard.service.QueryExperienceService;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/** 问数运行概况按 Java 受理时间分组，返回读取时观察到的任务状态及本人当前评价。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class QueryExperienceServiceImpl implements QueryExperienceService {

    // 工作台服务目标是用户从受理到答案就绪的等候时间，独立于审计记录的 Python 流程慢查询阈值。
    private static final int TARGET_SECONDS = 10;
    private final QueryExperienceMapper mapper;
    private final IamS1CapabilityService capabilities;

    @Override
    public QueryExperienceVO summary(Long userId, String period) {
        LocalDateTime asOf = LocalDateTime.now().withNano(0);
        Window window = Window.of(period, asOf);
        List<Long> auditSources = sources(userId, "audit:view");
        List<Long> feedbackSources = sources(userId, "governance:field:view");

        // 两域可来自完全不同的角色绑定，任何一域都不能借另一区域的分母或来源访问明细。
        return new QueryExperienceVO(period, asOf, window.start(), window.end(), TARGET_SECONDS,
                auditSources.isEmpty() ? null : usage(window, auditSources, "today".equals(period)),
                feedbackSources.isEmpty() ? null : feedback(window, feedbackSources, "today".equals(period)));
    }

    @Override
    public Page<QueryExperienceDetailVO> details(Long userId, QueryExperienceDetailsQuery query) {
        LocalDateTime asOf = query.getAsOf();
        if (asOf == null || asOf.getNano() != 0 || asOf.isAfter(LocalDateTime.now()) || asOf.getYear() < 1001) {
            throw new BusinessException(400, "请使用工作台统计返回的时间锚点");
        }
        Window window = Window.of(query.getPeriod(), asOf);
        Page<QueryExperienceDetailVO> page = query.toMpPage();
        if ("slow".equals(query.getKind())) {
            // 只接受服务端输出的自然桶起点，避免任意范围拼接或沿用昨日/七天的旧筛选。
            List<Long> scope = requiredSources(userId, "audit:view");
            Window bucket = window.bucketStarting(query.getBucketStart());
            return mapper.slowPage(page, bucket.scope(scope));
        }
        if ("feedback".equals(query.getKind()) && query.getBucketStart() == null) {
            return mapper.dislikePage(page, window.scope(requiredSources(userId, "governance:field:view")));
        }
        throw new BusinessException(400, "无效的工作台明细或时间桶条件");
    }

    private QueryExperienceVO.Usage usage(Window window, List<Long> sources, boolean compare) {
        QueryExperienceScopeQuery scope = window.scope(sources);
        List<QueryExperienceCounts> rows = mapper.buckets(scope);
        Map<Integer, QueryExperienceCounts> bySlot = rows.stream()
                .collect(Collectors.toMap(QueryExperienceCounts::slot, Function.identity()));
        List<QueryExperienceVO.Bucket> buckets = new ArrayList<>();

        // SQL 只返回有任务的时间段；创建时间覆盖的空桶仍有已知的 0 次提交及不可计算的平均值。
        for (LocalDateTime start = window.start(); start.isBefore(window.end());
             start = start.plusMinutes(window.bucketMinutes())) {
            QueryExperienceCounts row = bySlot.get(buckets.size());
            long measured = row == null ? 0 : row.measured();
            Window bucket = window.bucketStarting(start);
            buckets.add(new QueryExperienceVO.Bucket(start, bucket.end(), measured,
                    row == null ? 0 : row.slow(), measured == 0 ? null : (double) row.durationSum() / measured));
        }

        long measured = sum(rows, QueryExperienceCounts::measured);
        // 最近秩 ceil(.95*n) = n - floor(n/20)，无样本的区域不读分位数也不显示零秒。
        Long p95 = measured == 0 ? null : mapper.rankedDuration(scope, measured - measured / 20 - 1);
        Double previous = compare ? mapper.average(window.previousDay().scope(sources)) : null;
        return new QueryExperienceVO.Usage(sum(rows, QueryExperienceCounts::total),
                sum(rows, QueryExperienceCounts::completed), measured, sum(rows, QueryExperienceCounts::failed),
                sum(rows, QueryExperienceCounts::timedOut), sum(rows, QueryExperienceCounts::processing),
                sum(rows, QueryExperienceCounts::cancelled), sum(rows, QueryExperienceCounts::clarification),
                measured == 0 ? null : (double) sum(rows, QueryExperienceCounts::durationSum) / measured,
                p95, sum(rows, QueryExperienceCounts::slow), previous, List.copyOf(buckets));
    }

    private QueryExperienceVO.Feedback feedback(Window window, List<Long> sources, boolean compare) {
        // 未评价及改票均以当前唯一有效票为准；点踩与反馈修复后的 VERIFIED 均不能作为正确率样本。
        QueryExperienceVotes current = mapper.votes(window.scope(sources));
        QueryExperienceVotes previous = compare ? mapper.votes(window.previousDay().scope(sources)) : null;
        return new QueryExperienceVO.Feedback(current.eligible(), current.likes(), current.dislikes(),
                previous == null ? null : previous.likes(), previous == null ? null : previous.dislikes());
    }

    private static long sum(List<QueryExperienceCounts> rows, ToLongFunction<QueryExperienceCounts> metric) {
        return rows.stream().mapToLong(metric).sum();
    }

    private List<Long> sources(Long userId, String function) {
        return capabilities.responsibleDatasourcesWithFunction(userId, function).stream()
                .map(IamS1DatasourceRefVO::id).toList();
    }

    private List<Long> requiredSources(Long userId, String function) {
        List<Long> scope = sources(userId, function);
        if (scope.isEmpty()) throw new BusinessException(403, "无权查看该类问数明细");
        return scope;
    }

    /** 所有范围使用业务服务的本地自然日，与 query_task DATETIME 的写入规则一致。 */
    private record Window(LocalDateTime start, LocalDateTime end, int bucketMinutes) {
        static Window of(String period, LocalDateTime asOf) {
            LocalDateTime midnight = asOf.toLocalDate().atStartOfDay();
            if (period == null) throw new BusinessException(400, "请选择问数统计周期");
            return switch (period) {
                case "today" -> new Window(midnight, asOf, 30);
                case "yesterday" -> new Window(midnight.minusDays(1), midnight, 60);
                case "week" -> new Window(midnight.minusDays(6), asOf, 24 * 60);
                default -> throw new BusinessException(400, "不支持的问数统计周期");
            };
        }

        Window previousDay() {
            return new Window(start.minusDays(1), end.minusDays(1), bucketMinutes);
        }

        Window bucketStarting(LocalDateTime bucket) {
            if (bucket == null || bucket.isBefore(start) || !bucket.isBefore(end) || bucket.getNano() != 0
                    || Duration.between(start, bucket).getSeconds() % (bucketMinutes * 60L) != 0) {
                throw new BusinessException(400, "所选时段不属于当前问数统计周期");
            }
            LocalDateTime next = bucket.plusMinutes(bucketMinutes);
            return new Window(bucket, next.isBefore(end) ? next : end, bucketMinutes);
        }

        QueryExperienceScopeQuery scope(List<Long> sources) {
            return new QueryExperienceScopeQuery(sources, start, end, bucketMinutes, TARGET_SECONDS);
        }
    }
}
