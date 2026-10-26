package com.dataocean.module.fieldsemantic.service.impl;

import com.dataocean.module.fieldsemantic.entity.dto.BusinessKnowledgeDraftDTO;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 复用仓库 H2/Mock 配置，定向方法选择避免把父类 21 项累计为新覆盖。 */
class GovernanceKindsProbeTest extends GovernanceWorkflowPersistenceTest {
    @ParameterizedTest
    @ValueSource(strings={"DATASOURCE","TABLE","RELATION","METRIC"})
    void reviewedKindsRetainExactDependenciesAndInvalidateOnSourceChangeAndRevocation(String kind) {
        var customer = new DbTableMeta(); customer.setId(94L); customer.setDatasourceId(5L);
        customer.setSnapshotId(80L); customer.setTableName("customers"); customer.setGovernanceStatus("NORMAL"); tables.insert(customer);
        for (long id : List.of(92L, 93L)) {
            var col = new DbColumnMeta(); col.setId(id); col.setDatasourceId(5L); col.setSnapshotId(80L);
            col.setTableMetaId(id==92L ? 94L : 90L); col.setTableName(id==92L ? "customers" : "orders");
            col.setColumnName(id==92L ? "id" : "customer_id"); col.setDataType("BIGINT"); col.setGovernanceStatus("NORMAL"); columns.insert(col);
        }
        for (long id : List.of(91L, 92L, 93L)) {
            String table=id==92L ? "customers" : "orders";
            String name=id==91L ? "amount" : id==92L ? "id" : "customer_id";
            when(semantic.reviewedContext(id,80L)).thenReturn(Map.of("qualified",true,
                    "sourceIds",List.of(id,id+100,id+200,id+300),"dependencies",List.of("table:"+table,"column:"+table+"."+name),
                    "body","合成审核解释 "+name,"contentHash","fixture-"+id));
        }
        var request = new BusinessKnowledgeDraftDTO(); request.setKind(kind); request.setKnowledgeKey("kind-probe");
        request.setTitle("离线治理类型验证"); request.setEvidence("仅用于隔离测试的合成业务资料");
        request.setEvidenceReference("fixture:governance/kinds"); request.setEvidenceConfirmed(true);
        request.setBody(switch(kind) {
            case "DATASOURCE" -> Map.of("description","订单和客户测试源","scope","合成订单业务");
            case "TABLE" -> Map.of("description","订单","grain","一行一订单");
            case "RELATION" -> Map.of("description","订单归属客户","joinCondition","orders.customer_id = customers.id",
                    "cardinality","N:1","aggregationNotes","按订单粒度汇总，客户主键唯一");
            default -> Map.of("description","金额总和","formula","SUM(orders.amount)","conditions","无");
        });
        request.setDependencies(switch(kind) {
            case "RELATION" -> List.of("table:orders","column:orders.customer_id","table:customers","column:customers.id");
            case "METRIC" -> List.of("table:orders","column:orders.amount");
            default -> List.of("table:orders");
        });
        Long id=((Number)knowledge.save(80L,request,9L).get("id")).longValue();
        assertThat(knowledge.facts(5L,80L)).isEmpty();
        knowledge.action(id,1,"SUBMIT","",9L); knowledge.action(id,2,"APPROVE","核对合成资料",9L);
        assertThat(knowledge.facts(5L,80L)).isEmpty();
        knowledge.action(id,3,"RECHECK","",9L);
        var facts=knowledge.facts(5L,80L); assertThat(facts).hasSize(1);
        assertThat(facts.get(0).get("factType")).isEqualTo("BUSINESS_"+kind);
        assertThat((List<String>)facts.get(0).get("dependencies")).containsExactlyInAnyOrderElementsOf(request.getDependencies());
        knowledge.validateFact(5L,80L,facts.get(0),(String)facts.get(0).get("body"));
        assertThatThrownBy(()->knowledge.validateFact(5L,80L,facts.get(0),"擅自改写"));
        jdbc.update("UPDATE db_table_meta SET table_comment='changed' WHERE id=90");
        assertThat(knowledge.facts(5L,80L)).isEmpty();
        assertThat(knowledge.membershipValid(5L,80L,List.of(id.toString()))).isFalse();
        jdbc.update("UPDATE db_table_meta SET table_comment=NULL WHERE id=90");
        assertThat(knowledge.facts(5L,80L)).hasSize(1);
        request.setExpectedRevisionId(id); request.setExpectedVersion(4);
        knowledge.save(80L,request,9L);
        assertThat(knowledge.facts(5L,80L)).hasSize(1);
        knowledge.action(id,4,"REVOKE","撤回合成资料",9L);
        assertThat(knowledge.facts(5L,80L)).isEmpty();
    }
}
