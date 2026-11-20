package com.dataocean.module.governance.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.PageQuery;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.mapper.CatalogMapper.Observation;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Entry;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.datasource.service.GovernanceNotes;
import com.dataocean.module.governance.entity.po.GovernanceGenerationPO;
import com.dataocean.module.governance.entity.po.GovernanceItemPO;
import com.dataocean.module.governance.entity.vo.GovernanceCandidateVO.Definition;
import com.dataocean.module.governance.mapper.GovernanceDiscoveryMapper;
import com.dataocean.module.governance.mapper.GovernanceDiscoveryMapper.Candidate;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Basis;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Field;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Pair;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Proposal;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Scope;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.StructureRef;
import com.dataocean.module.governance.service.GovernanceDiscoveryWire.Table;
import com.dataocean.module.governance.service.GovernanceTarget;
import com.dataocean.module.iam.service.PermissionService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * 验证只有同源的可定位依据才能成为候选。
 */
class GovernanceDiscoveryTest {

    /** 固定两端同名字段，具体约束由测试提供。 */
    private static final Pair KEY = new Pair("11", "22");

    /** 捕获持久化边界。 */
    private final GovernanceDiscoveryMapper mapper = mock(GovernanceDiscoveryMapper.class);

    /**
     * 以当前同源目录回读候选时提供源上下文。
     */
    private final GovernanceCatalog catalog = mock(GovernanceCatalog.class);

    /**
     * 提供已确认的表说明和字段定义。
     */
    private final GovernanceEntries entries = mock(GovernanceEntries.class);

    /**
     * 沿用业务服务的类型化 JSON 序列化。
     */
    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());

    /**
     * 默认无原资料查看资格，可单独验证正文披露。
     */
    private final PermissionService permissions = mock(PermissionService.class);

    /**
     * 使用真实候选验证逻辑。
     */
    private final GovernanceDiscovery discovery =
            new GovernanceDiscovery(mapper, catalog, entries, mock(GovernanceNotes.class), permissions, json);

    /**
     * 只有同名字段的关系建议不能进入业务记录。
     */
    @Test
    void rejectsSameNameWithoutActualEvidence() {
        discovery.finish(task(), directory(List.of()), List.of(), List.of(relation()));
        verifyNoInteractions(mapper);
    }

    /**
     * 真外键对应的候选只建立一条，并绑定两端对象和待确认修订。
     */
    @Test
    void recordsOneCandidateForBothSidesOfCollectedKey() {
        discovery.finish(task(), directory(List.of(List.of(KEY))), List.of(), List.of(relation()));
        verify(mapper)
                .add(anyLong(), eq("fixture-task"), eq(2L), eq(1L), eq("RELATION"), anyString(), eq(4L), anyString());
        verify(mapper).attach(anyLong(), eq(1L));
        verify(mapper).attach(anyLong(), eq(2L));
        verify(mapper).revise(anyLong(), anyLong(), eq(1), eq("CANDIDATE"), anyString(), eq("[]"), eq(7L));
    }

    /**
     * 旧依据失效后，同一业务定义能形成新候选，重复发现仍只入库一次。
     */
    @Test
    void rediscoversSameDefinitionWhenStructureRevisionChanges() {
        var persisted = new HashMap<String, String>();
        when(mapper.evidenceForIdentity(eq(2L), eq("RELATION"), anyString()))
                .thenAnswer(invocation -> persisted.get(invocation.getArgument(2)));
        doAnswer(invocation -> {
                    // 在内存保留已入库候选的唯一身份和原依据。
                    persisted.put(invocation.getArgument(5), invocation.getArgument(7));
                    return null;
                })
                .when(mapper)
                .add(anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());

        var original = directory(List.of(List.of(KEY)));
        var updatedRefs = new HashMap<>(original.refs());
        updatedRefs.put("11", new StructureRef("1", "18", "11", "19", "products.product_id", "bigint"));
        var updated = new GovernanceDiscovery.DiscoveryInput(original.scope(), updatedRefs);
        discovery.finish(task(), original, List.of(), List.of(relation()));
        discovery.finish(task(), updated, List.of(), List.of(relation()));
        discovery.finish(task(), updated, List.of(), List.of(relation()));
        assertEquals(2, persisted.size());
        verify(mapper, times(2))
                .add(anyLong(), anyString(), anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    /**
     * 资料确实发送给模型时，即使无引用，候选正文仍需要资料查看权。
     */
    @Test
    void guardsDefinitionEvenWhenModelDidNotCiteInputs() {
        when(catalog.source(2L, false)).thenReturn(mock(Source.class));
        var definition = new Definition("商品数", "在册商品数", "COUNT(*)", "件", List.of(), List.of("11"));
        var stored = new Candidate(
                17L,
                "fixture-task",
                2L,
                "METRIC",
                4L,
                json.write(new Basis(List.of(), List.of(), false)),
                1,
                "CANDIDATE",
                json.write(definition),
                "[]",
                true);
        when(mapper.list(any(), eq(2L), eq(1L), eq("METRIC")))
                .thenReturn(new Page<Candidate>(1, 10, 1).setRecords(List.of(stored)));

        assertNull(discovery
                .list(2L, 1L, "METRIC", new PageQuery())
                .getItems()
                .get(0)
                .getDefinition());
        when(permissions.has("business-notes.view")).thenReturn(true);
        assertEquals(
                definition,
                discovery
                        .list(2L, 1L, "METRIC", new PageQuery())
                        .getItems()
                        .get(0)
                        .getDefinition());
    }

    /**
     * 平台库回读的小编号依赖应参与同一比较，变化后仍能提示复核。
     */
    @Test
    void detectsOnlyRealChangesInConfirmedDependencies() {
        var source = mock(Source.class);
        when(source.id()).thenReturn(2L);
        when(catalog.source(2L, false)).thenReturn(source);
        var observation = new Observation(3L, 4L, 1L, 8L, null, null, null, null, null);
        var table = new Entry(1L, 1, false, observation, List.of());

        // 固定一条使用已确认表说明和字段定义的指标。
        var item = new GovernanceItemPO();
        item.setHeadRevisionId(12L);
        item.setLastConfirmedRevisionId(12L);
        var context = mock(GovernanceEntries.Context.class);
        when(context.item()).thenReturn(item);
        when(context.availability()).thenReturn("AVAILABLE");
        when(context.entry()).thenReturn(table);
        when(context.columnRevision()).thenReturn(9L);
        when(entries.get(source, GovernanceTarget.table(2L, 1L))).thenReturn(context);
        when(entries.get(source, GovernanceTarget.column(2L, 1L, 11L))).thenReturn(context);

        // JSON 库按实际最小数字类型读取这份已保存依赖。
        var field = new StructureRef("1", "8", "11", "9", "products.product_id", "bigint");
        var basis = json.write(new Basis(List.of(field), List.of(), false));
        var definition = json.write(
                new Definition("商品数", "在册商品数", "COUNT(DISTINCT products.product_id)", "件", List.of(), List.of("11")));
        var stored = new Candidate(
                17L,
                "fixture-task",
                2L,
                "METRIC",
                4L,
                basis,
                3,
                "CONFIRMED",
                definition,
                """
                [{"object":1,"kind":"TABLE","target":1,"revision":12},
                 {"object":1,"kind":"COLUMN","target":11,"revision":12}]
                """,
                false);
        var page = new Page<Candidate>(1, 10, 1).setRecords(List.of(stored));
        when(mapper.list(any(), eq(2L), eq(1L), eq("METRIC"))).thenReturn(page);
        assertEquals(
                "CONFIRMED",
                discovery
                        .list(2L, 1L, "METRIC", new PageQuery())
                        .getItems()
                        .get(0)
                        .getState());

        // 最新确认指向另一修订，候选仍回读原版本并提示复核。
        item.setHeadRevisionId(13L);
        item.setLastConfirmedRevisionId(13L);
        var changed =
                discovery.list(2L, 1L, "METRIC", new PageQuery()).getItems().get(0);
        assertEquals("REVIEW", changed.getState());
        assertEquals(3, changed.getVersion());
    }

    /**
     * 构造实际治理队列已固定的来源身份。
     */
    private GovernanceGenerationPO task() {
        var task = new GovernanceGenerationPO();
        task.setRequestId("fixture-task");
        task.setSourceId(2L);
        task.setObjectId(1L);
        task.setSnapshotId(4L);
        task.setRequestedBy(7L);
        task.setDiscoveryKind("RELATION");
        return task;
    }

    /**
     * 分别提供有、无完整采集键时的相同字段名。
     */
    private GovernanceDiscovery.DiscoveryInput directory(List<List<Pair>> foreignKeys) {
        var left =
                new Table("1", "products", List.of(new Field("11", "product_id", "bigint", null, true)), foreignKeys);
        var right = new Table("2", "display", List.of(new Field("22", "product_id", "bigint", null, true)), List.of());
        return new GovernanceDiscovery.DiscoveryInput(
                new Scope("RELATION", "1", List.of(left, right)),
                Map.of(
                        "11", new StructureRef("1", "8", "11", "9", "products.product_id", "bigint"),
                        "22", new StructureRef("2", "10", "22", "15", "display.product_id", "bigint")));
    }

    /**
     * 同一个候选正文是否成立取决于真实目录，而不是模型置信度。
     */
    private Proposal relation() {
        return new Proposal(
                "商品与展示", "products.product_id = display.product_id", "一对一", "", List.of(KEY), List.of(), List.of());
    }
}
