package com.dataocean.module.datasource.service;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO.Scope;
import com.dataocean.module.datasource.mapper.CatalogMapper;
import com.dataocean.module.datasource.mapper.CatalogMapper.Observation;
import com.dataocean.module.datasource.mapper.CatalogObjectMapper;
import com.dataocean.module.datasource.mapper.CollectionSnapshotMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper;
import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper.Column;
import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper.Fact;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 治理工作台读取来源快照和事实的内部边界，由调用方检查治理动作权限。
 */
@Service
@RequiredArgsConstructor
public class GovernanceCatalog {

    /**
     * 真实目录指针及来源事务锁。
     */
    private final DatasourceMapper sources;

    /**
     * 已完成对账的成功快照。
     */
    private final CollectionSnapshotMapper snapshots;

    /**
     * 权威目录观察和归属检查。
     */
    private final CatalogMapper catalog;

    /**
     * 解析对象世代和留档状态。
     */
    private final CatalogObjectMapper objects;

    /**
     * 来源字段和结构变化中供治理复用的查询。
     */
    private final GovernanceCatalogMapper changes;

    /**
     * 在读快照或持有源写锁时取得当前有效目录上下文。
     */
    public Source source(long id, boolean lock) {
        var source = lock ? sources.lock(id) : sources.get(id);
        if (source == null) {
            // 消除不存在来源与越界资源的差异。
            throw BizIllegalException.missing();
        }
        Long configuration = source.getCurrentConnectionRevisionId();
        Long snapshotId = source.getCurrentSnapshotId();
        var snapshot = snapshotId == null ? null : snapshots.selectById(snapshotId);
        var at = snapshot == null ? null : snapshot.getCommittedAt();
        boolean pending = at == null || source.getScopeChangedAt() != null
                && source.getScopeChangedAt().isAfter(at);
        return new Source(id, source.getName(), source.getLifecycle(), configuration, snapshotId,
                source.getCatalogVersion(), pending, at, source.getStaleSince(), scope(id, configuration),
                snapshot == null ? null : scope(id, snapshot.getConfigRevisionId()));
    }

    /**
     * 确保对象、快照、作用域和各字段均属于当前成功目录。
     */
    public Entry current(Source source, long id) {
        if (source.snapshotId() == null) {
            // 没有成功快照就没有正式治理依据。
            return null;
        }
        return entry(source.id(), catalog.object(
                source.id(), source.snapshotId(), id, source.configurationId(), true, false));
    }

    /**
     * 读取当前成功目录中有界的可治理对象，不把源外目录传给工作流。
     */
    public List<Entry> peers(Source source, long anchor, int limit) {
        if (source.snapshotId() == null) {
            // 首次采集前没有可用于发现的目录。
            return List.of();
        }
        return catalog.members(source.snapshotId()).stream()
                .map(Observation::objectId).filter(id -> id != null && id != anchor)
                .distinct().sorted().limit(limit)
                .map(id -> current(source, id))
                .filter(entry -> entry != null && !entry.retired() && pending(source.id(), entry.id()) == 0)
                .toList();
    }

    /**
     * 读取两侧确切成功观察，字段名字只在对应的固定观察中用于定位。
     */
    public Entry observed(long source, Long observation) {
        return observation == null ? null : entry(source, catalog.object(source, null, observation, 0, false, true));
    }

    /**
     * 为明确删除的条目找回最后一次成功结构。
     */
    public Entry historical(long source, long object, Long column) {
        return observed(source, changes.lastObservation(source, object, column));
    }

    /**
     * 只回读本事务提交的确证来源变化。
     */
    public List<Fact> facts(SourceStructureChanged event) {
        return changes.facts(event.sourceId(), event.taskId(), event.changeId());
    }

    /**
     * 限定当前对象所有未核实事实，避免分页隐藏真正的治理阻塞。
     */
    public int pending(long source, long object) {
        return changes.pending(source, object);
    }

    /**
     * 将实际治理影响返回原变化，后续由来源入口审核查看权限。
     */
    public void link(long change, String kind, long ref) {
        changes.addImpact(change, kind, ref);
    }

    /**
     * 回写来源处理阶段，沿用原事实、版本及核实依据。
     */
    public void stage(long source, long change, String stage) {
        changes.stage(source, change, stage);
    }

    /**
     * 数据源模块只输出当前配置的安全范围。
     */
    private Scope scope(long source, Long revision) {
        if (revision == null) {
            // 未填写的来源草稿没有采集范围。
            return null;
        }
        var configuration = sources.revision(source, revision).configuration();
        return new Scope(configuration.getDatabaseType(), configuration.getDatabaseName(), configuration.getSchemas());
    }

    /**
     * 拒绝把未建立稳定身份的观察当作已有治理条目。
     */
    private Entry entry(long source, Observation observation) {
        if (observation == null || observation.objectId() == null || observation.revisionId() == null) {
            // 未知身份在数据源侧核实。
            return null;
        }
        var object = objects.selectById(observation.objectId());
        if (object == null || object.getSourceId() != source) {
            // 固定观察仍需要归属检查。
            throw BizIllegalException.missing();
        }
        return new Entry(object.getId(), object.getGeneration(), object.getRetiredAt() != null,
                observation, changes.columns(observation.revisionId()));
    }

    /**
     * 仅传递治理所需的非秘密来源状态。
     *
     * @param id 数据源编号
     * @param name 当前源名称
     * @param lifecycle 来源生命周期
     * @param configurationId 当前生效的连接修订
     * @param snapshotId 当前成功快照编号
     * @param catalogVersion 当前目录版本
     * @param scopePending 当前范围尚未成功采集
     * @param successfulAt 最近成功采集时刻
     * @param staleSince 目录进入陈旧状态的时刻
     * @param effectiveScope 当前配置范围
     * @param snapshotScope 实际成功快照范围
     */
    public record Source(
            long id, String name, String lifecycle, Long configurationId, Long snapshotId, int catalogVersion,
            boolean scopePending, LocalDateTime successfulAt, LocalDateTime staleSince, Scope effectiveScope,
            Scope snapshotScope) {}

    /**
     * 一份已确认身份的结构和它的稳定字段。
     *
     * @param id 对象稳定编号
     * @param generation 对象世代
     * @param retired 该身份当前是否已确定移除
     * @param observation 固定的成功观察
     * @param columns 同一对象结构修订的字段
     */
    public record Entry(long id, int generation, boolean retired, Observation observation, List<Column> columns) {

        /**
         * 源端名称仅在该份确切结构中向前或向后定位字段。
         */
        public Column named(String name) {
            return columns.stream().filter(column -> column.name().equals(name)).findFirst().orElse(null);
        }

        /**
         * 当前字段必须是对象结构的实际成员。
         */
        public Column column(long id) {
            return columns.stream().filter(column -> column.columnId() == id).findFirst().orElse(null);
        }
    }
}
