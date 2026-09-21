package com.dataocean.module.metadata.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.dto.IamS1FieldProtectionSaveDTO;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
import com.dataocean.module.permission.s1.service.IamS1FieldProtectionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 3 聚焦测试：字段治理“掩码候选”确认。
 *
 * <p>这里的行为直接决定“确认后到底有没有真的脱敏”，以及重复确认会不会累积多条 ACTIVE 保护。</p>
 */
class MetadataMaskCandidateServiceImplTest {

    @Test
    void confirmWritesS1FieldProtectionWithProtocolVersion() {
        Fixture fixture = new Fixture();

        fixture.service.confirm(9L, 100L, "PHONE");

        ArgumentCaptor<IamS1FieldProtectionSaveDTO> captor =
                ArgumentCaptor.forClass(IamS1FieldProtectionSaveDTO.class);
        verify(fixture.fieldProtectionService).saveProtection(org.mockito.ArgumentMatchers.eq(9L), captor.capture());
        IamS1FieldProtectionSaveDTO saved = captor.getValue();
        // 回归：validateEnvelope 强制要求协议版本，漏设会让确认接口直接失败。
        assertThat(saved.getProtocolVersion()).isEqualTo("IAM-SIMPLE-1");
        assertThat(saved.getProtectionLevel()).isEqualTo("MASKED");
        assertThat(saved.getMaskPolicy()).isEqualTo("PHONE");
        assertThat(saved.getColumnMetaId()).isEqualTo(77L);
        verify(fixture.entityService).updateById(any(MetadataEntity.class));
    }

    @Test
    void confirmIsIdempotentForTheSamePolicy() {
        Fixture fixture = new Fixture();
        // 同列已有同策略 ACTIVE 保护：V55 没有唯一约束，再插一条就会累积重复记录。
        IamS1FieldProtection existing = new IamS1FieldProtection();
        existing.setId(55L);
        existing.setMaskPolicy("PHONE");
        when(fixture.fieldProtectionMapper.selectList(any())).thenReturn(List.of(existing));

        fixture.service.confirm(9L, 100L, "PHONE");

        verify(fixture.fieldProtectionService, never()).saveProtection(any(), any());
        verify(fixture.fieldProtectionService, never()).revokeProtection(any(), any(), any());
    }

    @Test
    void confirmReplacesExistingProtectionWhenPolicyChanges() {
        Fixture fixture = new Fixture();
        IamS1FieldProtection existing = new IamS1FieldProtection();
        existing.setId(55L);
        existing.setMaskPolicy("EMAIL");
        when(fixture.fieldProtectionMapper.selectList(any())).thenReturn(List.of(existing));

        fixture.service.confirm(9L, 100L, "PHONE");

        // 旧策略必须先撤销，否则两条 ACTIVE 并存会让 Resolver 取值依赖插入顺序。
        verify(fixture.fieldProtectionService).revokeProtection(9L, 55L, "掩码候选重新确认，替换旧保护");
        verify(fixture.fieldProtectionService).saveProtection(any(), any());
    }

    @Test
    void confirmFailsWhenCandidateMarkCannotBeCleared() {
        Fixture fixture = new Fixture();
        // 候选标记清理失败必须让整个确认失败：吞掉异常会留下“保护已生效但候选还在”，
        // 再次确认会基于旧候选重复写入。
        when(fixture.entityService.updateById(any(MetadataEntity.class)))
                .thenThrow(new RuntimeException("数据库不可用"));

        Throwable thrown = catchThrowable(() -> fixture.service.confirm(9L, 100L, "PHONE"));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("清除掩码候选标记失败");
    }

    @Test
    void confirmRejectsBlankMaskStrategy() {
        Fixture fixture = new Fixture();

        Throwable thrown = catchThrowable(() -> fixture.service.confirm(9L, 100L, "  "));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        verify(fixture.fieldProtectionService, never()).saveProtection(any(), any());
    }

    @Test
    void confirmRequiresResolvableDatasourceAndSnapshot() {
        Fixture fixture = new Fixture();
        // 实体元数据里没有 datasource_id / snapshot_id 时必须拒绝，而不是按 null 写保护。
        MetadataEntity entity = columnEntity();
        entity.setEntityMetadata("{}");
        when(fixture.entityService.getById(100L)).thenReturn(entity);

        Throwable thrown = catchThrowable(() -> fixture.service.confirm(9L, 100L, "PHONE"));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("解析数据源");
    }

    @Test
    void confirmSerialisesOnTheColumnRowBeforeReadingTheCandidate() {
        // 并发幂等的关键顺序：先取字段元数据行锁，再做当前读判断候选是否已被处理。
        // 顺序反了（先读后锁）就会出现两个事务都读到“候选仍在”并各插一条保护。
        Fixture fixture = new Fixture();

        fixture.service.confirm(9L, 100L, "PHONE");

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
                fixture.columnMetaMapper, fixture.entityService);
        inOrder.verify(fixture.columnMetaMapper).selectForUpdate(any(), any(), any());
        inOrder.verify(fixture.entityService).getEntityByIdForUpdate(100L);
    }

    @Test
    void confirmIsIdempotentWhenTheCandidateWasAlreadyHandled() {
        Fixture fixture = new Fixture();
        // 锁内当前读发现 pending_mask 已被另一个事务清掉：幂等成功，不重复写保护。
        MetadataEntity handled = columnEntity();
        handled.setEntityMetadata("{\"datasource_id\":5,\"snapshot_id\":8}");
        when(fixture.entityService.getEntityByIdForUpdate(100L)).thenReturn(handled);

        fixture.service.confirm(9L, 100L, "PHONE");

        verify(fixture.fieldProtectionService, never()).saveProtection(any(), any());
        verify(fixture.entityService, never()).updateById(any(MetadataEntity.class));
    }

    @Test
    void confirmRollsBackWhenTheCandidateRowWasChangedByAnotherTransaction() {
        Fixture fixture = new Fixture();
        // updateById 返回 false 表示更新 0 行：必须回滚，
        // 否则会留下“保护已写入但候选没清掉”的不一致状态。
        when(fixture.entityService.updateById(any(MetadataEntity.class))).thenReturn(false);

        Throwable thrown = catchThrowable(() -> fixture.service.confirm(9L, 100L, "PHONE"));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("已被其他操作更新");
    }

    @Test
    void rejectOnlyClearsTheCandidateMark() {
        Fixture fixture = new Fixture();

        fixture.service.reject(9L, 100L);

        verify(fixture.fieldProtectionService, never()).saveProtection(any(), any());
        verify(fixture.entityService).updateById(any(MetadataEntity.class));
    }

    private static MetadataEntity columnEntity() {
        MetadataEntity entity = new MetadataEntity();
        entity.setId(100L);
        entity.setEntityType(MetadataEntity.TYPE_COLUMN);
        entity.setFqn("ds.db.orders.phone");
        entity.setName("phone");
        entity.setEntityMetadata("{\"datasource_id\":5,\"snapshot_id\":8,\"pending_mask\":{}}");
        return entity;
    }

    private static final class Fixture {
        private final MetadataEntityService entityService = mock(MetadataEntityService.class);
        private final DbColumnMetaMapper columnMetaMapper = mock(DbColumnMetaMapper.class);
        private final IamS1FieldProtectionMapper fieldProtectionMapper = mock(IamS1FieldProtectionMapper.class);
        private final IamS1FieldProtectionService fieldProtectionService =
                mock(IamS1FieldProtectionService.class);
        private final MetadataMaskCandidateServiceImpl service = new MetadataMaskCandidateServiceImpl(
                entityService, columnMetaMapper, fieldProtectionMapper, fieldProtectionService);

        {
            // resolveTarget 用普通读拿数据源/快照；锁后重读用当前读。
            when(entityService.getById(100L)).thenReturn(columnEntity());
            when(entityService.getEntityByIdForUpdate(100L)).thenReturn(columnEntity());
            DbColumnMeta column = new DbColumnMeta();
            column.setId(77L);
            when(columnMetaMapper.selectForUpdate(any(), any(), any())).thenReturn(column);
            when(fieldProtectionMapper.selectList(any())).thenReturn(List.of());
            when(entityService.updateById(any(MetadataEntity.class))).thenReturn(true);
        }
    }
}
