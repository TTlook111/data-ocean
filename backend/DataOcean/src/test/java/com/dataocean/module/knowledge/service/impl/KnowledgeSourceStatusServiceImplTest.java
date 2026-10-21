package com.dataocean.module.knowledge.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.entity.po.*;
import com.dataocean.module.knowledge.support.KnowledgeSnapshotFactValidator;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class KnowledgeSourceStatusServiceImplTest {
    final KnowledgeDocHelper helper = mock(KnowledgeDocHelper.class);
    final KnowledgeSnapshotFactValidator validator = mock(KnowledgeSnapshotFactValidator.class);
    final KnowledgeSourceStatusServiceImpl service = new KnowledgeSourceStatusServiceImpl(helper, validator);
    void document() {
        when(helper.requireDoc(1L)).thenReturn(KnowledgeDoc.builder().id(1L).datasourceId(5L).currentVersion(2).content("已保存正文").build());
        when(helper.requireVersion(1L, 2)).thenReturn(KnowledgeDocVersion.builder().metadataSnapshotId(88L).content("已保存正文").build());
    }
    @Test void validSourcesDoNotMeanPublicationOrBuildWasTriggered() {
        document();
        assertThat(service.status(1L).valid()).isTrue();
        verify(validator).validate(5L, 88L, "已保存正文");
    }
    @Test void withdrawnTermShowsBusinessReasonWithoutAlteringPublishedVersion() {
        document();
        doThrow(new BusinessException("术语审核来源已变化")).when(validator).validate(5L, 88L, "已保存正文");
        var result = service.status(1L);
        assertThat(result.valid()).isFalse();
        assertThat(result.reasons().get(0)).contains("业务术语已变化").doesNotContain("marker");
    }
    @Test void missingSnapshotIsNotReportedAsValid() {
        when(helper.requireDoc(1L)).thenReturn(KnowledgeDoc.builder().id(1L).currentVersion(1).build());
        when(helper.requireVersion(1L, 1)).thenThrow(new BusinessException("文档缺少来源快照"));
        assertThat(service.status(1L).valid()).isFalse();
        verifyNoInteractions(validator);
    }
}
