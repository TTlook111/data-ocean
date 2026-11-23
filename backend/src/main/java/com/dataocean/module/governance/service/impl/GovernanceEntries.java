package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper.Column;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Entry;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.governance.entity.dto.EntryPreconditionDTO;
import com.dataocean.module.governance.entity.po.GovernanceImpactPO;
import com.dataocean.module.governance.entity.po.GovernanceItemPO;
import com.dataocean.module.governance.entity.vo.GovernanceBasisVO;
import com.dataocean.module.governance.mapper.GovernanceImpactMapper;
import com.dataocean.module.governance.mapper.GovernanceItemMapper;
import com.dataocean.module.governance.service.GovernanceTarget;

import java.util.Objects;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 在权威成功目录中核对条目身份、状态、并发和固定结构。
 */
@Component
@RequiredArgsConstructor
public class GovernanceEntries {

    /**
     * B 批结构身份和当前来源投影。
     */
    private final GovernanceCatalog catalog;

    /**
     * 已物化的治理业务身份。
     */
    private final GovernanceItemMapper items;

    /**
     * 仅确认的移除可进入历史办理界面。
     */
    private final GovernanceImpactMapper impacts;

    /**
     * 打开当前条目或已明确保留的历史来源。
     */
    public Context get(Source source, GovernanceTarget target) {
        var item = find(target);
        var entry = catalog.current(source, target.object());
        var field = entry == null || !target.isColumn() ? null : entry.column(target.target());
        boolean hasCurrent = entry != null && (!target.isColumn() || field != null);
        boolean removed = item != null
                && impacts.exists(Wrappers.<GovernanceImpactPO>lambdaQuery()
                        .eq(GovernanceImpactPO::getItemId, item.getId())
                        .eq(GovernanceImpactPO::getImpactKind, "REMOVAL"));

        if (!hasCurrent) {
            // 历史来源仍按原身份定位，无法读取的结构不办理业务修改。
            entry = catalog.historical(source.id(), target.object(), target.isColumn() ? target.target() : null);
            field = entry == null || !target.isColumn() ? null : entry.column(target.target());
        }
        if (entry == null || target.isColumn() && field == null) {
            // 禁止通过猜测字段编号拼接不同来源。
            throw BizIllegalException.missing();
        }

        int pending = catalog.pending(source.id(), target.object());
        String availability = availability(source, removed || entry.retired(), pending > 0, hasCurrent);
        return new Context(target, entry, field, item, availability, pending);
    }

    /**
     * 给列表和详情采用同一来源状态优先级。
     */
    public String availability(Source source, boolean removed, boolean pending, boolean current) {
        if (removed) {
            // 只有明确移除可走专门的确认动作。
            return "REMOVED";
        }
        if (!"ENABLED".equals(source.lifecycle())) {
            // 停用、退役和来源草稿均不开放业务变更。
            return "READ_ONLY";
        }
        if (pending) {
            // 未完成的技术事实由 B 批继续核实。
            return "PENDING_SOURCE_REVIEW";
        }
        if (source.scopePending()) {
            // 范围变更直到适用的完整成功采集后开放。
            return "SCOPE_PENDING";
        }
        return current ? "AVAILABLE" : "INACCESSIBLE";
    }

    /**
     * 源写锁内检查客户端版本，不让旧草稿覆盖来源或编辑变化。
     */
    public void verify(Context context, EntryPreconditionDTO precondition, boolean removal) {
        String required = removal ? "REMOVED" : "AVAILABLE";
        if (!required.equals(context.availability())) {
            // 当前事实优先于之前打开的页面。
            throw BizIllegalException.state("当前来源或条目状态无法办理此操作");
        }
        Integer version = context.item() == null ? null : context.item().getVersion();
        if (!Objects.equals(version, precondition.getExpectedVersion())) {
            // 复核新增与编辑版本均参与并发校验。
            throw BizIllegalException.conflict();
        }
        if (context.structureRevision() != Ids.parse(precondition.getExpectedStructureRevisionId())) {
            // 单字段操作固定字段修订，表说明固定对象修订。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "目录结构已变化，请重新核对");
        }
    }

    /**
     * 源锁内首次为实际写入或实际复核建立稳定条目。
     */
    public GovernanceItemPO ensure(GovernanceTarget target, int generation) {
        var current = find(target);
        if (current != null) {
            // 同一对象或字段永远复用原稳定条目。
            return current;
        }
        var item = new GovernanceItemPO();
        item.setSourceId(target.source());
        item.setObjectId(target.object());
        item.setGeneration(generation);
        item.setKind(target.kind());
        item.setTargetId(target.target());
        item.setVersion(1);
        items.insert(item);
        return item;
    }

    /**
     * 向现有条目的版本写入实际修改，源锁已经固定当前状态。
     */
    public void update(GovernanceItemPO item, Long head, Long confirmed) {
        int affected = items.update(Wrappers.<GovernanceItemPO>lambdaUpdate()
                .eq(GovernanceItemPO::getId, item.getId())
                .eq(GovernanceItemPO::getVersion, item.getVersion())
                .set(GovernanceItemPO::getHeadRevisionId, head)
                .set(GovernanceItemPO::getLastConfirmedRevisionId, confirmed)
                .setIncrBy(GovernanceItemPO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
        if (affected != 1) {
            // 所有同源写入在同一事务链上，防御意外的旧版本提交。
            throw BizIllegalException.conflict();
        }
        item.setHeadRevisionId(head);
        item.setLastConfirmedRevisionId(confirmed);
        item.setVersion(item.getVersion() + 1);
    }

    /**
     * 定位同一来源内的物化身份。
     */
    public GovernanceItemPO find(GovernanceTarget target) {
        return items.selectOne(Wrappers.<GovernanceItemPO>lambdaQuery()
                .eq(GovernanceItemPO::getSourceId, target.source())
                .eq(GovernanceItemPO::getObjectId, target.object())
                .eq(GovernanceItemPO::getKind, target.kind())
                .eq(GovernanceItemPO::getTargetId, target.target()));
    }

    /**
     * 从持久化条目还原已核验的稳定目标。
     */
    public GovernanceTarget target(GovernanceItemPO item) {
        return new GovernanceTarget(item.getSourceId(), item.getObjectId(), item.getKind(), item.getTargetId());
    }

    /**
     * 取固定结构的安全引用。
     */
    public GovernanceBasisVO basis(long snapshot, long object, Long column) {
        return new GovernanceBasisVO(
                Long.toString(snapshot), Long.toString(object), column == null ? null : Long.toString(column));
    }

    /**
     * 已结合来源事实、业务身份和可用性的条目上下文。
     *
     * @param target 稳定条目目标
     * @param entry 来源的确切成功结构
     * @param field 字段条目实际对应的结构
     * @param item 已物化的业务身份
     * @param availability 当前能否办理业务变更
     * @param pending 当前对象尚未结束的技术事实数
     */
    public record Context(
            GovernanceTarget target,
            Entry entry,
            Column field,
            GovernanceItemPO item,
            String availability,
            int pending) {

        /**
         * 按业务条目层次选择外部并发核对的修订。
         */
        public long structureRevision() {
            return field == null ? entry.observation().revisionId() : field.revisionId();
        }

        /**
         * 当前办理时的列版本。
         */
        public Long columnRevision() {
            return field == null ? null : field.revisionId();
        }
    }
}
