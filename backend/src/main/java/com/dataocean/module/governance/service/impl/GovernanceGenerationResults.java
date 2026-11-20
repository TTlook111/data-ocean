package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceNotes.Note;
import com.dataocean.module.governance.entity.po.GovernanceEvidencePO;
import com.dataocean.module.governance.entity.po.GovernanceGenerationPO;
import com.dataocean.module.governance.entity.po.GovernanceModelCallPO;
import com.dataocean.module.governance.entity.po.GovernanceSuggestionPO;
import com.dataocean.module.governance.entity.vo.GenerationSuggestionVO;
import com.dataocean.module.governance.mapper.GovernanceEvidenceMapper;
import com.dataocean.module.governance.mapper.GovernanceGenerationRecords;
import com.dataocean.module.governance.mapper.GovernanceModelCallMapper;
import com.dataocean.module.governance.mapper.GovernanceSuggestionMapper;
import com.dataocean.module.governance.service.GovernanceGenerationWire;
import com.dataocean.module.model.service.ModelRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 校验模型回执并在任务事务中保存治理结果和据实诊断。
 */
@Service
@RequiredArgsConstructor
class GovernanceGenerationResults {

    /**
     * 保存实际回执和诊断信息。
     */
    private final GovernanceModelCallMapper calls;

    /**
     * 保存和读取单条治理建议。
     */
    private final GovernanceSuggestionMapper suggestions;

    /**
     * 保存建议绑定的原始资料引用。
     */
    private final GovernanceEvidenceMapper evidenceRows;

    /**
     * 查询固定任务目标并记录资料实际取用状态。
     */
    private final GovernanceGenerationRecords records;

    /**
     * 核对候选作用域并保存发现结果。
     */
    private final GovernanceDiscovery discovery;

    /**
     * 核对业务定义字段及其值。
     */
    private final GovernanceContent content;

    /**
     * 核对实际引用及其披露内容。
     */
    private final GovernanceEvidence evidence;

    /**
     * 序列化有明确契约的固定结果。
     */
    private final JsonCodec json;

    /**
     * 所有写入都在调用方已持有源锁的任务事务内执行。
     */
    void finish(
            GovernanceGenerationPO task,
            GovernanceCatalog.Source source,
            GovernanceEntries.Context entry,
            List<Note> inputs,
            GovernanceGenerationWire.Result result) {
        var notes = new HashMap<String, Note>();
        inputs.forEach(note -> notes.put(Long.toString(note.noteId()), note));
        var reported = result.call();
        if (task.getModelStartedAt() == null
                || task.getModelRevisionId() == null
                || reported == null
                || !Long.toString(task.getModelRevisionId()).equals(reported.revisionId())
                || result.usedNoteIds() == null
                || !notes.keySet().containsAll(result.usedNoteIds())) {
            // 回执只接受本次已获外发资格的模型及固定输入。
            throw BizIllegalException.invalid("模型回执与固定任务不一致");
        }

        if (task.getDiscoveryKind() == null) {
            // 单条建议和候选发现共用回执校验，结果按自己的类型落库。
            saveSuggestion(task, entry, notes, result);
        } else {
            discovery.finish(
                    task, discovery.input(source, entry.entry(), task.getDiscoveryKind()), inputs, result.candidates());
            recordCall(task, "SUCCEEDED", null, reported, result.inputLimits());
        }

        for (var noteId : notes.keySet()) {
            // 实际发送过的资料参与候选权限判定，独立于模型引用。
            records.inputState(
                    task.getRequestId(),
                    Long.parseLong(noteId),
                    result.usedNoteIds().contains(noteId) ? "USED" : "SKIPPED");
        }
    }

    /**
     * 写入单条定义建议及其实际依据，业务验证先于所有写入。
     */
    private void saveSuggestion(
            GovernanceGenerationPO task,
            GovernanceEntries.Context entry,
            Map<String, Note> inputs,
            GovernanceGenerationWire.Result result) {
        var definition = content.normalize(entry.target().isColumn(), result.content());
        var allowed =
                Set.copyOf(json.strings(records.target(task.getRequestId()).contentPaths()));
        if (definition.isEmpty() || !allowed.containsAll(definition.propertyNames()) || result.evidence() == null) {
            // 回执不能增补未获准的业务字段。
            throw BizIllegalException.invalid("模型未给出可核对的建议");
        }

        var refs = new ArrayList<GovernanceEvidencePO>();
        for (var citation : result.evidence()) {
            // 校验引用对应实际发送的资料和建议中的内容项。
            if (!definition.has(citation.contentPath()) || !result.usedNoteIds().contains(citation.noteId())) {
                throw BizIllegalException.invalid("模型建议的来源超出固定输入");
            }
            refs.add(evidence.model(task.getSourceId(), inputs.get(citation.noteId()), citation));
        }

        var call = recordCall(task, "SUCCEEDED", null, result.call(), result.inputLimits());
        var suggestion = new GovernanceSuggestionPO();
        suggestion.setRequestId(task.getRequestId());
        suggestion.setItemId(entry.item().getId());
        suggestion.setModelCallId(call.getId());
        suggestion.setContent(json.write(definition));
        suggestions.insert(suggestion);
        for (var ref : refs) {
            // 关联原始建议和已验证的业务出处。
            ref.setSuggestionId(suggestion.getId());
            ref.setCreatedBy(task.getRequestedBy());
            evidenceRows.insert(ref);
        }
    }

    /**
     * 只为领取过模型外发资格的失败保留诊断。
     */
    void recordFailure(GovernanceGenerationPO task, String failureCode) {
        recordCall(task, "FAILED", failureCode, null, null);
    }

    /**
     * 以平台固定的任务修订记录可审计的调用元数据。
     */
    private GovernanceModelCallPO recordCall(
            GovernanceGenerationPO task,
            String state,
            String error,
            GovernanceGenerationWire.Call report,
            List<String> inputLimits) {
        var call = new GovernanceModelCallPO();
        call.setRequestId(task.getRequestId());
        if (task.getDiscoveryKind() != null) {
            // 发现任务按关联或指标节点记账。
            call.setNodeCode(discovery.node(task.getDiscoveryKind()));
        } else {
            call.setNodeCode(
                    "COLUMN".equals(records.target(task.getRequestId()).kind())
                            ? ModelRegistry.FIELD_EXPLANATION
                            : ModelRegistry.TABLE_DESCRIPTION);
        }
        call.setState(state);
        call.setFailureCode(error);
        if (report != null) {
            // 实际模型、用量和提供方标识可为空。
            call.setModelName(report.modelName());
            call.setProviderCallId(report.providerCallId());
            call.setElapsedMs(report.elapsedMs());
            call.setInputTokens(report.inputTokens());
            call.setOutputTokens(report.outputTokens());
        }
        if (inputLimits != null && !inputLimits.isEmpty()) {
            // 诊断只保存白名单中的明确预算原因。
            var allowed = Set.of("COLUMN_LIMIT", "ATTACHMENT_UNAVAILABLE", "INPUT_BUDGET");
            call.setInputLimits(String.join(
                    ",",
                    inputLimits.stream()
                            .filter(Objects::nonNull)
                            .filter(allowed::contains)
                            .distinct()
                            .sorted()
                            .toList()));
        }
        calls.insert(call);
        return call;
    }

    /**
     * 读取已授权访问的任务建议。
     */
    GenerationSuggestionVO view(String request, boolean showDiagnostics) {
        var suggestion = suggestions.selectOne(
                Wrappers.<GovernanceSuggestionPO>lambdaQuery().eq(GovernanceSuggestionPO::getRequestId, request));
        if (suggestion == null) {
            // 发现任务或执行中的任务没有单条建议。
            return null;
        }
        return new GenerationSuggestionVO(
                Long.toString(suggestion.getId()),
                json.mapper().readTree(suggestion.getContent()),
                suggestionEvidence(suggestion.getId()).stream()
                        .map(evidence::view)
                        .toList(),
                showDiagnostics ? Long.toString(suggestion.getModelCallId()) : null);
    }

    /**
     * 读取指定建议，调用方核对其固定目标和当前资格后才能采用。
     */
    GovernanceSuggestionPO suggestion(long id) {
        return suggestions.selectById(id);
    }

    /**
     * 读取建议固定的依据集合，待采用时按被选内容项核对。
     */
    List<GovernanceEvidencePO> suggestionEvidence(long id) {
        return evidenceRows.selectList(
                Wrappers.<GovernanceEvidencePO>lambdaQuery().eq(GovernanceEvidencePO::getSuggestionId, id));
    }
}
