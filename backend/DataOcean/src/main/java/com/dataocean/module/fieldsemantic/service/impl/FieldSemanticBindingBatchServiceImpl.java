package com.dataocean.module.fieldsemantic.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticBindingBatchConfirmDTO;
import com.dataocean.module.fieldsemantic.entity.dto.FieldSemanticBindingConfirmDTO;
import com.dataocean.module.fieldsemantic.entity.vo.FieldSemanticBindingBatchResultVO;
import com.dataocean.module.fieldsemantic.entity.vo.FieldSemanticPendingBindingVO;
import com.dataocean.module.fieldsemantic.service.FieldSemanticBindingBatchService;
import com.dataocean.module.fieldsemantic.service.FieldSemanticService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 先完整预校验同源权限，再通过 Spring 事务代理逐字段提交，保留部分成功明细。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldSemanticBindingBatchServiceImpl implements FieldSemanticBindingBatchService {
    private final FieldSemanticService semanticService;

    @Override
    public List<FieldSemanticPendingBindingVO> pending(Long snapshotId, Long userId) {
        return semanticService.pendingBindingConfirmations(snapshotId, userId);
    }

    @Override
    public FieldSemanticBindingBatchResultVO confirm(Long snapshotId,
                                                      FieldSemanticBindingBatchConfirmDTO request,
                                                      Long userId) {
        List<FieldSemanticBindingBatchConfirmDTO.Item> items = request.getItems();
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new BusinessException(400, "批量确认需选择 1 至 100 个字段");
        }
        Set<Long> seen = new HashSet<>();
        for (FieldSemanticBindingBatchConfirmDTO.Item item : items) {
            if (item == null || item.getColumnMetaId() == null || item.getExpectedVersion() == null
                    || item.getEvidence() == null || item.getEvidence().isBlank()) {
                throw new BusinessException(400, "每项都必须提供字段、预期版本和核验依据");
            }
            if (!seen.add(item.getColumnMetaId())) throw new BusinessException(400, "批次中不能重复选择同一字段");
        }

        // 权限与真实来源预校验在任何写入之前完成，避免一批请求部分写入未授权字段。
        for (FieldSemanticBindingBatchConfirmDTO.Item item : items) {
            semanticService.validateBindingConfirmation(item.getColumnMetaId(), snapshotId, userId);
        }

        FieldSemanticBindingBatchResultVO result = new FieldSemanticBindingBatchResultVO();
        result.setSnapshotId(snapshotId);
        result.setTotalCount(items.size());
        for (FieldSemanticBindingBatchConfirmDTO.Item item : items) {
            FieldSemanticBindingBatchResultVO.Item itemResult = new FieldSemanticBindingBatchResultVO.Item();
            itemResult.setColumnMetaId(item.getColumnMetaId());
            FieldSemanticBindingConfirmDTO confirm = new FieldSemanticBindingConfirmDTO();
            confirm.setExpectedVersion(item.getExpectedVersion());
            confirm.setEvidence(item.getEvidence());
            try {
                semanticService.confirmBinding(item.getColumnMetaId(), snapshotId, confirm, userId);
                itemResult.setSuccess(true);
                itemResult.setMessage("来源已确认，并生成了当前绑定的审核证据");
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (BusinessException exception) {
                itemResult.setSuccess(false);
                itemResult.setMessage(exception.getMessage());
                result.setFailedCount(result.getFailedCount() + 1);
            } catch (RuntimeException exception) {
                log.error("批量确认字段语义来源失败 snapshotId={} columnMetaId={}", snapshotId,
                        item.getColumnMetaId(), exception);
                itemResult.setSuccess(false);
                itemResult.setMessage("处理失败，请刷新后重试");
                result.setFailedCount(result.getFailedCount() + 1);
            }
            result.getItems().add(itemResult);
        }
        return result;
    }
}
