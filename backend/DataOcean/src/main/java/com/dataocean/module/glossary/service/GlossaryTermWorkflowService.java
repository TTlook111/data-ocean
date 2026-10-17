package com.dataocean.module.glossary.service;

import com.dataocean.module.glossary.entity.dto.*;
import com.dataocean.module.glossary.entity.po.GlossaryTerm;
import com.dataocean.module.glossary.entity.vo.TermWorkspaceVO;
import java.util.List;
import java.util.Map;

public interface GlossaryTermWorkflowService {
    GlossaryTerm save(Long glossaryId, Long termId, TermDraftDTO draft);
    void action(Long termId, String action, TermActionDTO request);
    void delete(Long termId, Long revision);
    void link(Long termId, Long entityId, Long revision, boolean remove);
    TermWorkspaceVO workspace(Long termId);
    List<Long> linkedIds(Long termId);
    Map<String, Object> approvedFact(Long termId);
    boolean factValid(Long termId, String hash);
}
