package com.dataocean.module.query.service.impl;

import com.dataocean.module.permission.s1.entity.IamS1RowCondition;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.permission.s1.entity.vo.IamS1FieldProtectionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1GrantSourceVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1RowConditionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1RowPredicateVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1TablePermissionVO;
import com.dataocean.module.permission.s1.mapper.IamS1RowConditionMapper;
import com.dataocean.module.query.entity.dto.IamS1ExecutionBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IamS1RowBindingServiceImplTest {
    @Mock
    private IamS1RowConditionMapper rowConditionMapper;
    private IamS1RowBindingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IamS1RowBindingServiceImpl(rowConditionMapper, new ObjectMapper());
    }

    @Test
    void buildsEphemeralBindingFromStructuredServerValue() {
        IamS1RowCondition row = row(91L, "\"华东\"", null);
        when(rowConditionMapper.selectByGrantIds(List.of(9L))).thenReturn(List.of(row));
        var result = service.build(snapshot("grant-9-condition-91"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getReference()).isEqualTo("grant-9-condition-91");
        assertThat(result.get(0).getValue()).isEqualTo("华东");
    }

    @Test
    void rejectsUnresolvedParameterReferenceFailClosed() {
        IamS1RowCondition row = row(91L, null, "current_department");
        when(rowConditionMapper.selectByGrantIds(List.of(9L))).thenReturn(List.of(row));
        assertThatThrownBy(() -> service.build(snapshot("grant-9-condition-91")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("服务端绑定");
    }

    private IamS1DataAuthorizationSnapshot snapshot(String bindingReference) {
        var predicate = new IamS1RowPredicateVO(2L, "region", "EQ", "STRING", null, bindingReference);
        var condition = new IamS1RowConditionVO("ALL", List.of(predicate));
        var source = new IamS1GrantSourceVO(9L, "USER", 7L, "用户个人授权", null, "MANUAL", null,
                LocalDateTime.now().minusDays(1), null, List.of("id"), condition);
        var field = new IamS1FieldProtectionVO(1L, "orders", "id", "NORMAL", null, "normal");
        var table = new IamS1TablePermissionVO(true, "ALLOWED", "orders", List.of("id"), List.of(source), List.of(field), List.of());
        return new IamS1DataAuthorizationSnapshot(true, "ALLOWED", "IAM-SIMPLE-1", 7L, 1L, "db", 88L, 100L,
                LocalDateTime.now(), null, List.of(table));
    }

    private IamS1RowCondition row(Long id, String value, String parameterReference) {
        IamS1RowCondition row = new IamS1RowCondition();
        row.setId(id); row.setGrantId(9L); row.setMetadataSnapshotId(88L); row.setTableName("orders");
        row.setMatchType("ALL"); row.setSequenceNo(1); row.setColumnMetaId(2L); row.setColumnName("region");
        row.setOperatorCode("EQ"); row.setValueType("STRING"); row.setStructuredValueJson(value); row.setParameterReference(parameterReference);
        return row;
    }
}
