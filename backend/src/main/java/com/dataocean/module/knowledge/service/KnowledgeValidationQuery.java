package com.dataocean.module.knowledge.service;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Entry;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.datasource.service.GovernanceSourceRows;
import com.dataocean.module.datasource.service.GovernanceSourceRows.Count;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.governance.entity.vo.GovernanceCandidateVO.Definition;
import com.dataocean.module.governance.service.GovernanceKnowledgeService;
import com.dataocean.module.governance.service.GovernanceTarget;
import com.dataocean.module.knowledge.entity.dto.KnowledgeTestDraftDTO.CountRule;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper.Reference;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 从冻结且实际召回的治理依据构造首个事项的受控去重计数。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeValidationQuery {

    /**
     * 当前成功目录提供确切对象和字段名。
     */
    private final GovernanceCatalog catalog;

    /**
     * 指标和关联必须仍为确切审核修订。
     */
    private final GovernanceKnowledgeService governance;

    /**
     * 复用只读账号和固定来源连接。
     */
    private final GovernanceSourceRows rows;

    /**
     * 对确认定义按既有类型解码。
     */
    private final JsonCodec json;

    /**
     * 审核时检查文档依据能否组成当前支持的执行条件，无需连接业务源。
     */
    public void verify(Source source, List<Reference> evidence, CountRule rule) {
        resolve(source, evidence, rule);
    }

    /**
     * 运行时使用实际召回的依据解析同一条件，再用只读账号执行。
     */
    public Count execute(Source source, List<Reference> evidence, CountRule rule) throws SQLException {
        var plan = resolve(source, evidence, rule);
        return rows.countDistinct(
                source,
                plan.primary(),
                plan.key(),
                plan.detail(),
                plan.foreign(),
                plan.filter(),
                rule.getCategoryValue());
    }

    /**
     * 只接受双表单键关联、主键去重计数和主表字段等值过滤。
     */
    private CountPlan resolve(Source source, List<Reference> evidence, CountRule rule) {
        var filter = reference(evidence, "COLUMN", rule.getCategoryId());
        var metric = definition(source, reference(evidence, "METRIC", rule.getMetricId()));
        var relation = definition(source, reference(evidence, "RELATION", rule.getRelationId()));
        if (relation.getPairs() == null
                || relation.getPairs().size() != 1
                || metric.getFields() == null
                || metric.getRule() == null) {
            // 其他关联形状没有获准执行计划。
            throw unsupported();
        }
        var links = relation.getPairs().get(0);
        var left = reference(evidence, "COLUMN", links.left());
        var right = reference(evidence, "COLUMN", links.right());
        var key = left.objectId() == filter.objectId() ? left : right;
        var foreign = key == left ? right : left;
        if (key.objectId() != filter.objectId()
                || foreign.objectId() == key.objectId()
                || !metric.getFields().containsAll(List.of(links.left(), links.right(), rule.getCategoryId()))) {
            // 不能用另一张表、数量关系或未审核的列推导执行条件。
            throw unsupported();
        }
        var primary = catalog.current(source, key.objectId());
        var detail = catalog.current(source, foreign.objectId());
        String keyName = column(primary, key);
        String filterName = column(primary, filter);
        String foreignName = column(detail, foreign);
        var primaryShape = primary.observation().structure();
        var object = primaryShape.object();
        var primaryKeys = primaryShape.primaryKeys();
        String formula = metric.getRule().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String field = (object.name() + "." + keyName).toLowerCase(Locale.ROOT);
        String qualified = (object.schema() + "." + field).toLowerCase(Locale.ROOT);
        if (primaryKeys.size() != 1
                || !keyName.equals(primaryKeys.get(0).field())
                || (!formula.equals("count(distinct" + field + ")")
                        && !formula.equals("count(distinct" + qualified + ")"))) {
            // 指标只作已审核语义的白名单检查，原公式不发送给 JDBC。
            throw unsupported();
        }
        return new CountPlan(object, keyName, detail.observation().structure().object(), foreignName, filterName);
    }

    /**
     * 从本次文档或实际召回片段的同版引用中找到真实依据。
     */
    private Reference reference(List<Reference> evidence, String kind, String id) {
        long target = Ids.parse(id);
        return evidence.stream()
                .filter(item -> kind.equals(item.kind()) && item.targetId() == target)
                .findFirst()
                .orElseThrow(this::unsupported);
    }

    /**
     * 从类型化治理接口读取固定定义并检查修订身份。
     */
    private Definition definition(Source source, Reference ref) {
        var fact = governance.require(
                source, new GovernanceTarget(source.id(), ref.objectId(), ref.kind(), ref.targetId()));
        if (Ids.parse(fact.getRevisionId()) != ref.factRevisionId()) {
            // 执行依据变化后只能回到新候选。
            throw unsupported();
        }
        return json.read(fact.getContent().toString(), Definition.class);
    }

    /**
     * 当前目录字段必须与冻结的确切结构相同。
     */
    private String column(Entry entry, Reference ref) {
        var column = entry == null ? null : entry.column(ref.targetId());
        if (column == null || entry.retired() || column.revisionId() != ref.structureRevisionId()) {
            // 不使用含义不确定、消失或替代的列。
            throw unsupported();
        }
        return column.name();
    }

    /**
     * 向验证编排提供可控的条件错误。
     */
    private BizIllegalException unsupported() {
        return BizIllegalException.invalid("请选用文档中已确认的主键去重指标、单键关联及主表筛选字段");
    }

    /**
     * 已核实的查询对象与列名。执行时由只读源适配器绑定筛选值。
     */
    private record CountPlan(ObjectRef primary, String key, ObjectRef detail, String foreign, String filter) {}
}
