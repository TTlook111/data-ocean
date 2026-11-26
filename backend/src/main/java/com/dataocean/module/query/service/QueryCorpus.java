package com.dataocean.module.query.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper.Column;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.governance.service.GovernanceKnowledgeService;
import com.dataocean.module.governance.service.GovernanceTarget;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper.Chunk;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.mapper.KnowledgeReleaseCandidateMapper;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Evidence;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.model.service.ModelRegistry;
import com.dataocean.module.query.entity.dto.QueryEvidenceSnippetDTO;
import com.dataocean.module.query.entity.dto.QueryEvidenceSnippetDTO.ConfirmedMetricRules;
import com.dataocean.module.query.entity.dto.QueryEvidenceSnippetDTO.Metric;
import com.dataocean.module.query.entity.vo.QueryEvidenceVO;
import com.dataocean.module.query.service.QueryProtocol.Condition;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.Fragment;
import com.dataocean.module.query.service.QueryProtocol.Hit;
import com.dataocean.module.query.service.QueryProtocol.Index;
import com.dataocean.module.query.service.QueryTableSelection.Name;
import com.dataocean.module.query.service.QueryTableSelection.Scope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 从权威正式索引和固定治理引用解析本次可用的查询语料。
 */
@Service
@RequiredArgsConstructor
public class QueryCorpus {

    /**
     * 规划模型最多接收八份片段。
     */
    private static final int MAX_PLAN_EVIDENCE = 8;

    /** 同源正式版本及确认历史。 */
    private final KnowledgePublicationMapper publications;
    /** 复用当前治理禁止及正式知识使用控制。 */
    private final KnowledgeVersionService versions;
    /** 取发布候选内容指纹而非最新草稿。 */
    private final KnowledgeReleaseCandidateMapper candidates;
    /** 查阅固定片段及实际代次。 */
    private final KnowledgeBuildMapper builds;
    /** 已发布文档采用的确切依赖。 */
    private final KnowledgeDocumentMapper documents;
    /** 源端可信结构和实际标识。 */
    private final GovernanceCatalog catalog;
    /** 现行治理定义与确切修订。 */
    private final GovernanceKnowledgeService governance;
    /** 已启用的固定 Embedding 配置。 */
    private final ModelRegistry models;
    /** 已持久化的治理证据。 */
    private final JsonCodec json;

    /** 内部检索只选择当前可用白名单，超过搜索预算时明确失败。 */
    public List<Long> bindings(long source) {
        var rows = publications.current(new Page<>(1, 9, false), source, null, "", "", null);
        if (rows.getRecords().size() > 8) {
            throw QueryFailure.resource();
        }
        var allowed = new ArrayList<Long>();
        for (var row : rows.getRecords()) {
            // 暂停、撤下、待核实和禁止的版本不进入外发及召回语料。
            try {
                versions.requireForQuery(source, row.id());
                allowed.add(row.id());
            } catch (BizIllegalException unavailable) {
                continue;
            }
        }
        return allowed;
    }

    /** 继续使用原任务固定的生效入口，任何一项失效都要求结束旧流程。 */
    public List<Index> indexes(long source, List<Long> frozen, int remaining) {
        var allowed = new ArrayList<Index>();
        for (var id : frozen) {
            var binding = versions.requirePinnedForQuery(source, id);
            var candidate = candidates.selectById(binding.candidateId());
            var chunks = verifiedChunks(binding);
            if (candidate == null) {
                // 正式索引必须保留确切候选身份。
                throw QueryFailure.technical();
            }
            allowed.add(new Index(
                    Ids.text(binding.id()),
                    Ids.text(binding.candidateId()),
                    Ids.text(binding.documentRevisionId()),
                    candidate.getFingerprint(),
                    binding.collectionName(),
                    chunks.stream()
                            .map(chunk -> new Fragment(chunk.ordinal(), chunk.digest()))
                            .toList(),
                    models.invocation(binding.embeddingRevisionId(), Math.min(16, remaining))));
        }
        return allowed;
    }

    /**
     * 按实际库表身份补查本轮冻结的发布。匹配后仍用常规依据复核正文。
     */
    Scope targetedScope(long sourceId, List<Long> frozen, List<Basis> recalled, String question, String supplement) {
        var source = catalog.source(sourceId, false);
        var directories = new HashMap<Long, GovernanceCatalog.Entry>();
        var available = new ArrayList<TableHit>();
        for (var id : frozen) {
            // 撤权或发布失效时，不能用旧冻结结果继续外发。
            var binding = versions.requirePinnedForQuery(sourceId, id);
            for (var chunk : verifiedChunks(binding)) {
                // 根据固定片段实际发布的对象证据建立补查入口。
                var tables = new ArrayList<Name>();
                for (var evidence : json.read(chunk.evidence(), Evidence[].class)) {
                    // 只有已发布的表级证据才可用来定位候选。
                    if ("TABLE".equals(evidence.kind())) {
                        // 缓存当前源目录身份，不从用户输入拼接定位语句。
                        var entry = directories.computeIfAbsent(
                                Ids.parse(evidence.objectId()), objectId -> catalog.current(source, objectId));
                        if (entry != null && !entry.retired() && entry.generation() == evidence.generation()) {
                            // 已变更世代的旧片段不能补齐用户当前指定的对象。
                            tables.add(QueryTableSelection.name(
                                    entry.observation().structure().object()));
                        }
                    }
                }
                available.add(new TableHit(new Hit(Ids.text(id), chunk.ordinal(), chunk.digest()), Set.copyOf(tables)));
            }
        }
        var names = available.stream().flatMap(hit -> hit.tables().stream()).collect(Collectors.toSet());
        var requested = QueryTableSelection.requestedNames(names, question, supplement);
        if (requested.isEmpty()) {
            // 例如版本号等普通点分文本，继续沿用首轮召回。
            return new Scope(recalled, Set.of());
        }
        var matches = available.stream()
                .filter(hit -> hit.tables().containsAll(requested))
                .toList();
        if (matches.size() > MAX_PLAN_EVIDENCE) {
            // 无法在现有证据预算内核对全部精确候选时，保留资源不足状态。
            throw QueryFailure.resource();
        }
        var verified = matches.stream().map(hit -> basis(sourceId, hit.hit())).toList();
        return QueryTableSelection.matchingScope(verified, requested).orElseThrow(QueryFailure::knowledge);
    }

    /**
     * 记录固定片段身份和确切的表名，补查后仍使用常规依据复核。
     */
    private record TableHit(Hit hit, Set<Name> tables) {}

    /**
     * 复用已验证的构建片段清单，召回和补查均按同一发布代次定位。
     */
    private List<Chunk> verifiedChunks(Binding binding) {
        var build = builds.get(binding.buildId());
        var chunks = builds.chunks(binding.buildId());
        if (build == null || chunks.isEmpty() || !Objects.equals(build.verifiedChunks(), chunks.size())) {
            // 发布索引损坏属于技术失败，不能误判为没有相关知识。
            throw QueryFailure.technical();
        }
        return chunks;
    }

    /** 只读取实际召回的固定片段并提供该片段确切治理依据。 */
    public Basis basis(long sourceId, Hit hit) {
        var binding = versions.requirePinnedForQuery(sourceId, Ids.parse(hit.binding()));
        var source = catalog.source(sourceId, false);
        var chunk = builds.chunks(binding.buildId()).stream()
                .filter(row -> row.ordinal() == hit.ordinal() && row.digest().equals(hit.digest()))
                .findFirst()
                .orElseThrow(QueryFailure::technical);
        var refs = documents.references(binding.documentRevisionId());
        var indexed = List.of(json.read(chunk.evidence(), Evidence[].class));
        var tables = new LinkedHashMap<String, Table>();
        var indexedFields = new LinkedHashMap<String, Field>();
        var facts = new LinkedHashMap<String, GovernanceFactVO>();
        for (var evidence : indexed) {
            // 模型不能扩大索引和固定正文共同证明的范围。
            var ref = refs.stream()
                    .filter(r -> Ids.text(r.targetId()).equals(evidence.targetId())
                            && r.kind().equals(evidence.kind())
                            && Ids.text(r.objectId()).equals(evidence.objectId())
                            && Ids.text(r.factRevisionId()).equals(evidence.factRevisionId()))
                    .findFirst()
                    .orElseThrow(QueryFailure::technical);
            var entry = catalog.current(source, ref.objectId());
            if (entry == null || entry.retired() || entry.generation() != ref.generation()) {
                throw QueryFailure.knowledge();
            }
            var fact = governance.require(
                    source, new GovernanceTarget(sourceId, ref.objectId(), ref.kind(), ref.targetId()));
            if (!fact.getRevisionId().equals(evidence.factRevisionId())) {
                throw QueryFailure.knowledge();
            }
            facts.put(factKey(ref.kind(), evidence.targetId()), fact);
            String name = entry.observation().structure().object().name();
            if ("TABLE".equals(ref.kind())) {
                // 同名不同模式必须有明确指代，首条链路按逻辑表名做唯一定位。
                if (tables.putIfAbsent(name, new Table(entry, new LinkedHashMap<>())) != null) {
                    throw QueryFailure.knowledge();
                }
            }
            if ("COLUMN".equals(ref.kind())) {
                var column = entry.column(ref.targetId());
                if (column == null) {
                    throw QueryFailure.knowledge();
                }
                indexedFields.put(evidence.targetId(), new Field(name, column.name()));
            }
        }
        var labels = new LinkedHashMap<Field, String>();
        indexedFields.forEach((id, field) -> {
            var table = tables.get(field.table());
            if (table != null) {
                // 提供业务含义及源字段名，用于语义和输出权限核对。
                var fact = facts.get(factKey("COLUMN", id));
                String label = fact.getContent().path("meaning").asText(fact.getLabel());
                table.columns().put(field.column(), label);
                labels.put(field, label);
            }
        });
        return new Basis(source, binding, chunk.ordinal(), chunk.body(), tables, indexedFields, facts, labels);
    }

    /** 已确认目录与当前片段允许引用的字段。 */
    record Table(GovernanceCatalog.Entry entry, Map<String, String> columns) {}

    /** 不同治理类型的数字目标可以重号，实际证据沿类型共同定位。 */
    private static String factKey(String kind, String targetId) {
        return kind + ":" + targetId;
    }

    /**
     * 只在当前片段的已确认字段中读取可信目录列。
     */
    static Column confirmedColumn(Basis basis, Field field) {
        if (field == null || !basis.labels().containsKey(field)) {
            // 实际表有该列也不能替代当前片段对列的确认引用。
            return null;
        }
        var table = basis.tables().get(field.table());
        return table == null ? null : table.entry().named(field.column());
    }

    /**
     * 日期维度必须来自同版治理引用与原生 DATE 字段。
     */
    static boolean isDateField(Basis basis, Field field) {
        var column = confirmedColumn(basis, field);
        return column != null && "date".equalsIgnoreCase(column.nativeType());
    }

    /**
     * 普通表是当前编译器支持的物理连接对象。
     */
    static boolean ordinaryTable(Basis basis, String name) {
        return "TABLE"
                .equals(basis.tables()
                        .get(name)
                        .entry()
                        .observation()
                        .structure()
                        .object()
                        .type());
    }

    /** Java 唯一可信的片段结构，模型不能回填 SQL 标识。 */
    record Basis(
            Source source,
            Binding binding,
            int ordinal,
            String text,
            Map<String, Table> tables,
            Map<String, Field> fields,
            Map<String, GovernanceFactVO> facts,
            Map<Field, String> labels) {

        /** 同一片段上读取某类稳定目标的确切治理修订。 */
        GovernanceFactVO fact(String kind, long targetId) {
            return facts.get(factKey(kind, Ids.text(targetId)));
        }

        /** 模型上下文仅含已授权确认内容及逻辑表列。 */
        QueryEvidenceSnippetDTO snippet() {
            var columns = new LinkedHashMap<String, Map<String, String>>();
            tables.forEach((name, table) -> {
                var typed = new LinkedHashMap<String, String>();
                table.columns().forEach((column, label) -> {
                    // 编码与外键含义可能相似，规划时仍需要知道实际绑定类型。
                    typed.put(
                            column,
                            label + "（字段类型 " + table.entry().named(column).nativeType() + "）");
                });
                columns.put(name, typed);
            });
            var metrics = facts.values().stream()
                    .filter(fact -> "METRIC".equals(fact.getTarget().getKind()))
                    .map(fact -> new Metric(
                            fact.getLabel(),
                            fact.getContent().path("meaning").asText(),
                            fact.getContent().path("rule").asText(),
                            fact.getContent().path("usage").asText(),
                            confirmedMetricRules(fact)))
                    .toList();
            return new QueryEvidenceSnippetDTO(Ids.text(binding.id()), text, columns, metrics);
        }

        /**
         * 把已确认的列标识换成受控的逻辑表列，让两个模型节点可直接核对计划。
         */
        private ConfirmedMetricRules confirmedMetricRules(GovernanceFactVO fact) {
            var rules = QuerySql.confirmedMetricRules(this, fact);
            if (rules == null) {
                // 证据不全时保留原指标正文，由编译器拒绝该执行口径。
                return null;
            }
            return new ConfirmedMetricRules(
                    rules.getDateMode(),
                    "BUSINESS_DAY".equals(rules.getDateMode()) ? fields.get(rules.getDateFieldId()) : null,
                    rules.getFixedFilters().stream()
                            .map(filter -> new Condition(fields.get(filter.getFieldId()), filter.getValue()))
                            .toList(),
                    rules.getUnitMode(),
                    rules.getUnit(),
                    "COLUMN".equals(rules.getUnitMode()) ? fields.get(rules.getUnitFieldId()) : null);
        }

        /** 页面仅展示正式依据身份，不外发完整正文或索引字段。 */
        QueryEvidenceVO evidence() {
            return new QueryEvidenceVO(
                    binding.title(), binding.revision(), Ids.text(binding.documentRevisionId()), ordinal);
        }
    }
}
