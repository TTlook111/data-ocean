package com.dataocean.module.fieldsemantic.service;

import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticBindingBatchConfirmDTO;
import com.dataocean.module.fieldsemantic.entity.vo.FieldSemanticBindingBatchResultVO;
import com.dataocean.module.fieldsemantic.entity.vo.FieldSemanticPendingBindingVO;

import java.util.List;

public interface FieldSemanticBindingBatchService {
    List<FieldSemanticPendingBindingVO> pending(Long snapshotId, Long userId);
    FieldSemanticBindingBatchResultVO confirm(Long snapshotId, FieldSemanticBindingBatchConfirmDTO request, Long userId);
}
