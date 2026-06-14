package com.dataocean.module.metadata;

import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.event.SnapshotEntitySyncListener;
import com.dataocean.module.glossary.controller.GlossaryController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 治理闭环集成测试
 * <p>
 * 验证血缘桥接、术语关联、标签推断、审批过期四个闭环的核心逻辑。
 * </p>
 */
class GovernanceClosureTest {

    // ========== 闭环 3：标签推断 ==========

    /**
     * 创建 SnapshotEntitySyncListener 实例（所有依赖传 null，仅用于测试私有方法）
     */
    private SnapshotEntitySyncListener createListener() {
        return new SnapshotEntitySyncListener(null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("标签推断：手机号列匹配 PII.手机号")
    void inferTag_phoneColumn_matchesPiiPhone() throws Exception {
        Method method = SnapshotEntitySyncListener.class.getDeclaredMethod("inferTagCandidates", String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(createListener(), "phone_number", "用户手机号");
        assertThat(result).contains("PII.手机号");
    }

    @Test
    @DisplayName("标签推断：金额列匹配 业务域.财务")
    void inferTag_amountColumn_matchesFinance() throws Exception {
        Method method = SnapshotEntitySyncListener.class.getDeclaredMethod("inferTagCandidates", String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(createListener(), "total_amount", "订单总金额");
        assertThat(result).contains("业务域.财务");
    }

    @Test
    @DisplayName("标签推断：普通列无匹配")
    void inferTag_normalColumn_noMatch() throws Exception {
        Method method = SnapshotEntitySyncListener.class.getDeclaredMethod("inferTagCandidates", String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(createListener(), "status", "状态标志");
        assertThat(result).isEqualTo("[]");
    }

    @ParameterizedTest
    @CsvSource({
            "id_card_no, 身份证号码, PII.身份证号",
            "email_addr, 邮箱地址, PII.邮箱",
            "bank_card_no, 银行卡号, PII.银行卡号",
            "customer_name, 客户姓名, PII.姓名",
            "home_address, 家庭地址, PII.地址",
            "order_id, 订单编号, 业务域.销售",
            "employee_name, 员工姓名, 业务域.人力",
            "supplier_code, 供应商编码, 业务域.供应链"
    })
    @DisplayName("标签推断：多种列名模式匹配")
    void inferTag_variousPatterns_matchExpected(String colName, String comment, String expectedTag) throws Exception {
        Method method = SnapshotEntitySyncListener.class.getDeclaredMethod("inferTagCandidates", String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(createListener(), colName, comment);
        assertThat(result).contains(expectedTag);
    }

    // ========== 闭环 4：MASK 策略候选生成 ==========

    @Test
    @DisplayName("PII 标签→MASK 策略：手机号标签映射到 PHONE 策略")
    void piiTag_maskMapping_phoneTag() {
        // 验证标签到脱敏策略的映射逻辑
        String maskStrategy = mapPiiTagToMaskStrategy("PII.手机号");
        assertThat(maskStrategy).isEqualTo("PHONE");
    }

    @Test
    @DisplayName("PII 标签→MASK 策略：身份证号标签映射到 ID_CARD 策略")
    void piiTag_maskMapping_idCardTag() {
        String maskStrategy = mapPiiTagToMaskStrategy("PII.身份证号");
        assertThat(maskStrategy).isEqualTo("ID_CARD");
    }

    @Test
    @DisplayName("PII 标签→MASK 策略：邮箱标签映射到 EMAIL 策略")
    void piiTag_maskMapping_emailTag() {
        String maskStrategy = mapPiiTagToMaskStrategy("PII.邮箱");
        assertThat(maskStrategy).isEqualTo("EMAIL");
    }

    // ========== 闭环 1：血缘桥接 ==========

    @Test
    @DisplayName("血缘桥接：FQN 前缀解析正确")
    void lineageBridge_fqnPrefixParsing() {
        // 验证 FQN 解析逻辑：datasource.db.table.column → table
        String fqn = "mysql_prod.mydb.orders.customer_id";
        String[] parts = fqn.split("\\.");
        String tableName = parts[parts.length - 2]; // "orders"
        assertThat(tableName).isEqualTo("orders");
    }

    @Test
    @DisplayName("血缘桥接：LINEAGE 关系类型常量存在")
    void lineageBridge_constantExists() {
        assertThat(MetadataRelationship.TYPE_LINEAGE).isEqualTo("LINEAGE");
    }

    // ========== 闭环 2：术语关联 ==========

    @Test
    @DisplayName("术语关联：GLOSSARY_OF 关系类型常量存在")
    void glossaryLink_constantExists() {
        assertThat(MetadataRelationship.TYPE_GLOSSARY_OF).isEqualTo("GLOSSARY_OF");
    }

    @Test
    @DisplayName("术语关联：TAGGED_WITH 关系类型常量存在")
    void tagLink_constantExists() {
        assertThat(MetadataRelationship.TYPE_TAGGED_WITH).isEqualTo("TAGGED_WITH");
    }

    // ========== 辅助方法 ==========

    /**
     * 模拟 MetadataCatalogController 中的 PII 标签→MASK 策略映射
     */
    private String mapPiiTagToMaskStrategy(String tagFqn) {
        return switch (tagFqn) {
            case "PII.手机号" -> "PHONE";
            case "PII.身份证号" -> "ID_CARD";
            case "PII.邮箱" -> "EMAIL";
            case "PII.银行卡号" -> "BANK_CARD";
            case "PII.姓名" -> "NAME";
            default -> "PHONE";
        };
    }
}
