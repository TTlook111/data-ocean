package com.dataocean.module.knowledge.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.entity.vo.KnowledgeSourceStatusVO;
import com.dataocean.module.knowledge.support.KnowledgeSnapshotFactValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** 状态读取只校验来源，不发布文档、不触发构建。 */
@Service
@RequiredArgsConstructor
public class KnowledgeSourceStatusServiceImpl {
    private final KnowledgeDocHelper helper;
    private final KnowledgeSnapshotFactValidator validator;

    public KnowledgeSourceStatusVO status(Long id) {
        var doc = helper.requireDoc(id);
        Long snapshotId = null;
        try {
            var version = helper.requireVersion(id, doc.getCurrentVersion());
            snapshotId = version.getMetadataSnapshotId();
            if (!Objects.equals(doc.getContent(), version.getContent()))
                throw new BusinessException("文档与保存版本不一致");
            // 单篇文档可只承载快照的一部分；核对其声明的事实，整组覆盖仍由发布/构建链路校验。
            validator.validatePart(doc.getDatasourceId(), snapshotId, version.getContent());
            return new KnowledgeSourceStatusVO(true, snapshotId, List.of());
        } catch (BusinessException ex) {
            String reason = ex.getMessage().contains("术语")
                    ? "引用的业务术语已变化或缺少有效审核，请重新确认术语并生成新草稿。"
                    : "已保存版本的字段、快照或审核来源需重新核对；确认后再发布或构建。";
            return new KnowledgeSourceStatusVO(false, snapshotId, List.of(reason));
        }
    }
}
