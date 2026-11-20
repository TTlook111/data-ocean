package com.dataocean.module.governance.service.impl;

import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.module.datasource.service.GovernanceCatalog.Entry;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.governance.entity.vo.GovernanceFactOptionVO;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.governance.entity.vo.GovernanceTargetVO;
import com.dataocean.module.governance.mapper.GovernanceKnowledgeMapper;
import com.dataocean.module.governance.service.GovernanceKnowledgeService;
import com.dataocean.module.governance.service.GovernanceTarget;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 统一核对 D 批所用的 C 批确认，原文仍按各自权限管理。
 */
@Service
@RequiredArgsConstructor
public class GovernanceKnowledge implements GovernanceKnowledgeService {

    /**
     * 知识交接支持的治理类别。
     */
    private static final Set<String> KINDS = Set.of("TABLE", "COLUMN", "RELATION", "METRIC");

    /**
     * 读取源内稳定治理目标。
     */
    private final GovernanceKnowledgeMapper reads;

    /**
     * 表说明、字段的权威目录与复核状态。
     */
    private final GovernanceEntries entries;

    /**
     * 直接复用治理条目对确认和当前禁止的判断。
     */
    private final GovernanceQueries queries;

    /**
     * 关联及指标的已审核定义与依赖。
     */
    private final GovernanceDiscovery discovery;

    @Override
    public PageVO<GovernanceFactOptionVO> list(Source source, String kind, Long object, PageQuery query) {
        // 类型是业务白名单，搜索条件不得改变实际来源。
        if (kind != null && !KINDS.contains(kind)) {
            throw BizIllegalException.invalid("请选择治理条目类型");
        }
        var page = query.<GovernanceKnowledgeMapper.Ref>toFixedOrderMpPage();
        page.setOptimizeCountSql(false);
        return PageVO.from(reads.confirmed(page, source.id(), kind, object), row -> {
            var target = new GovernanceTarget(source.id(), row.objectId(), row.kind(), row.targetId());
            try {
                return new GovernanceFactOptionVO(target.view(), require(source, target), null);
            } catch (BizIllegalException rejected) {
                // 列表保留稳定定位，受限详情不泄露正文或来源。
                return new GovernanceFactOptionVO(target.view(), null, "当前定义待复核或无法查看");
            }
        });
    }

    @Override
    public GovernanceFactVO require(Source source, GovernanceTarget target) {
        if (source.id() != target.source() || !KINDS.contains(target.kind())) {
            throw BizIllegalException.missing();
        }
        if ("RELATION".equals(target.kind()) || "METRIC".equals(target.kind())) {
            // 候选服务读取其固定审核修订和依赖，不由知识入口重新解释规则。
            return discovery.confirmedForKnowledge(source, target);
        }
        if (!target.isColumn() && target.target() != target.object()) {
            throw BizIllegalException.missing();
        }
        var entry = entries.get(source, target);
        var item = queries.confirmedForKnowledge(entry);
        if (item == null) {
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "所选治理定义尚未确认、待复核或已禁止使用");
        }
        String label = qualified(entry.entry())
                + (target.isColumn() ? "." + entry.field().name() : " · 表说明");
        var dependencies = target.isColumn()
                ? List.of(GovernanceTarget.table(source.id(), target.object()).view())
                : List.<GovernanceTargetVO>of();
        return new GovernanceFactVO(
                target.view(),
                item.getRevisionId(),
                entry.entry().generation(),
                Long.toString(entry.structureRevision()),
                label,
                item.getContent(),
                dependencies);
    }

    /**
     * 展示名来源于当前稳定目录，仅用于定位。
     */
    static String qualified(Entry entry) {
        var object = entry.observation().structure().object();
        return object.schema() == null || object.schema().isBlank()
                ? object.name()
                : object.schema() + "." + object.name();
    }
}
