package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.datasource.service.GovernanceNotes;
import com.dataocean.module.datasource.service.GovernanceNotes.Note;
import com.dataocean.module.governance.entity.dto.EvidenceLocationDTO;
import com.dataocean.module.governance.entity.dto.GovernanceDraftInputDTO;
import com.dataocean.module.governance.entity.dto.GovernanceManualEvidenceDTO;
import com.dataocean.module.governance.entity.dto.GovernanceStructureRefDTO;
import com.dataocean.module.governance.entity.po.GovernanceEvidencePO;
import com.dataocean.module.governance.entity.vo.GovernanceEvidenceVO;
import com.dataocean.module.governance.mapper.GovernanceEvidenceMapper;
import com.dataocean.module.governance.service.GovernanceGenerationWire;
import com.dataocean.module.governance.service.impl.GovernanceEntries.Context;
import com.dataocean.module.iam.service.PermissionService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 管理业务说明和技术引用的固定证据、实际相关性及当前披露。
 */
@Component
@RequiredArgsConstructor
public class GovernanceEvidence {

    /**
     * 现有资料业务服务防止状态和历史修订绕过。
     */
    private final GovernanceNotes notes;

    /**
     * 从成功结构取用稳定字段与修订。
     */
    private final GovernanceCatalog catalog;

    /**
     * 固定证据版本。
     */
    private final GovernanceEvidenceMapper evidence;

    /**
     * 只按当前用户权利披露必要依据。
     */
    private final PermissionService permissions;

    /**
     * 定位结构证据的外部字符串引用。
     */
    private final GovernanceEntries entries;

    /**
     * 业务字段由其表或字段内容模型约束。
     */
    private final GovernanceContent content;

    /**
     * 解析限定位置结构。
     */
    private final JsonCodec json;

    /**
     * 生成不包含正文的稳定引用指纹。
     */
    private final SecretKeyProvider secrets;

    /**
     * 读取一个具体治理修订的引用。
     */
    public List<GovernanceEvidencePO> list(Long revision) {
        if (revision == null) {
            // 尚未保存的业务内容没有已采用依据。
            return List.of();
        }
        return evidence.selectList(Wrappers.<GovernanceEvidencePO>lambdaQuery()
                .eq(GovernanceEvidencePO::getItemRevisionId, revision)
                .orderByAsc(GovernanceEvidencePO::getContentPath, GovernanceEvidencePO::getLocatorKey));
    }

    /**
     * 区分继承与显式替换，匹配每个业务字段的当前依据。
     */
    public List<GovernanceEvidencePO> prepare(
            Source source, Context context, GovernanceDraftInputDTO draft, List<GovernanceEvidencePO> former) {
        var selected = new ArrayList<GovernanceEvidencePO>();
        for (var reference : former) {
            // 草稿更新可只改变定义文字，也可明确清空某一类证据。
            boolean keep = "STRUCTURE".equals(reference.getKind())
                    ? draft.getStructureRefs() == null : draft.getManualEvidence() == null;
            if (keep) {
                selected.add(reference);
            }
        }
        if (draft.getStructureRefs() != null) {
            // 技术对象必须是当前允许办理的同源结构。
            draft.getStructureRefs().forEach(reference -> selected.add(structure(source, context, reference)));
        }
        if (draft.getManualEvidence() != null) {
            // 新填资料和人工声明分别核对用途和实际出处。
            draft.getManualEvidence().forEach(reference -> selected.add(manual(source, context, reference)));
        }
        selected.sort(Comparator.comparing(GovernanceEvidencePO::getContentPath)
                .thenComparing(GovernanceEvidencePO::getLocatorKey));
        if (keys(selected).stream().distinct().count() != selected.size()) {
            // 重复关联不应生成不同语义的业务版本。
            throw BizIllegalException.invalid("同一业务内容存在重复引用");
        }
        return selected;
    }

    /**
     * 比较时只看经过归一化的字段、种类及实质定位。
     */
    public List<String> keys(List<GovernanceEvidencePO> refs) {
        return refs.stream().map(ref -> ref.getContentPath() + "/" + ref.getLocatorKey()).sorted().toList();
    }

    /**
     * 判定换版后具体片段是否仍可作为依据，允许标题变化及不相关正文改动。
     */
    public boolean applies(GovernanceEvidencePO ref, Note original, Note current) {
        if (current == null) {
            // 撤下的资料不能作为本次新确认的现行依据。
            return false;
        }
        if (ref.getAttachmentId() != null) {
            // 原件摘要一致时才认定附件引用未受影响。
            var previous = original.files().stream()
                    .filter(file -> file.id() == ref.getAttachmentId()).findFirst().orElse(null);
            return previous != null && current.files().stream()
                    .anyMatch(file -> file.digest().equals(previous.digest()));
        }
        var location = json.read(ref.getLocation(), EvidenceLocationDTO.class);
        String quoted = excerpt(original.body(), location);
        return current.body().contains(quoted);
    }

    /**
     * 保存新修订实际引用，并经 B 批登记防删除引用。
     */
    public void record(long source, long revision, List<GovernanceEvidencePO> selected) {
        for (var ref : selected) {
            // 历史出处从新行单独写入，无权修改原使用记录。
            ref.setId(null);
            ref.setItemRevisionId(revision);
            ref.setSuggestionId(null);
            ref.setCreatedAt(null);
            ref.setCreatedBy(UserContext.getUser());
            if ("NOTE".equals(ref.getKind())) {
                // 使用新的内容修订时仍要求资料有效且实际引用片段未失效。
                var note = verifyNote(ref);
                notes.reference(source, note, "GOV_ITEM_REVISION", Long.toString(revision));
            }
            evidence.insert(ref);
        }
    }

    /**
     * 确认前核对使用者能检查必要出处，读取仅用当前业务上下文。
     */
    public void confirm(Source source, List<GovernanceEvidencePO> references) {
        for (var ref : references) {
            // 证据角色和存储角色分别检查。
            if ("NOTE".equals(ref.getKind())) {
                permissions.require("business-notes.view");
                verifyNote(ref);
            } else if ("STRUCTURE".equals(ref.getKind())) {
                var current = catalog.current(source, ref.getObjectId());
                if (current == null || current.retired() || source.scopePending()
                        || catalog.pending(source.id(), ref.getObjectId()) > 0
                        || ref.getColumnId() != null && current.column(ref.getColumnId()) == null) {
                    // 删除或未核实依赖必须处理后才能确认本定义。
                    throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "引用的结构无法使用，请重新核对");
                }
            }
        }
    }

    /**
     * 证据详情按各所属入口重新鉴权，无法还原的历史仅给通用状态。
     */
    public GovernanceEvidenceVO view(GovernanceEvidencePO ref) {
        boolean redacted = "NOTE".equals(ref.getKind()) && !permissions.has("business-notes.view")
                || "STRUCTURE".equals(ref.getKind()) && !permissions.has("catalog.view");
        if (redacted) {
            // 受限资料的编号、附件、位置以及原文均不进入响应。
            return new GovernanceEvidenceVO(ref.getKind(), ref.getContentPath(), true, null, null, null,
                    null, null, null, null);
        }
        if ("NOTE".equals(ref.getKind())) {
            // 仅披露权限允许的确切修订和引用位置。
            var note = notes.revision(ref.getSourceId(), ref.getNoteId(), ref.getNoteRevisionId());
            return new GovernanceEvidenceVO("NOTE", ref.getContentPath(), false, null,
                    Long.toString(ref.getNoteId()), Long.toString(ref.getNoteRevisionId()), note.revision(),
                    Objects.toString(ref.getAttachmentId(), null),
                    json.read(ref.getLocation(), EvidenceLocationDTO.class), null);
        }
        var basis = ref.getSnapshotId() == null ? null
                : entries.basis(ref.getSnapshotId(), ref.getObjectRevisionId(), ref.getColumnRevisionId());
        return new GovernanceEvidenceVO(ref.getKind(), ref.getContentPath(), false, basis, null, null, null,
                null, null, ref.getManualBasis());
    }

    /**
     * 固定模型实际引用的输入编号和出处，禁止把未知来源写入原始建议。
     */
    public GovernanceEvidencePO model(long source, Note note, GovernanceGenerationWire.Evidence citation) {
        Long file = citation.attachmentId() == null ? null : Ids.parse(citation.attachmentId());
        var location = new EvidenceLocationDTO(file == null ? "BODY" : "ATTACHMENT", citation.start(), citation.end());
        if (file == null) {
            // 正文定位须落在真实固定资料内。
            excerpt(note.body(), location);
        } else if (note.files().stream().noneMatch(attachment -> attachment.id() == file)) {
            // 原件必须是当时实际固定的修订成员。
            throw BizIllegalException.missing();
        }
        var ref = base(source, citation.contentPath(), "NOTE",
                List.of(note.noteId(), note.revisionId(), file == null ? 0L : file, location));
        ref.setNoteId(note.noteId());
        ref.setNoteRevisionId(note.revisionId());
        ref.setAttachmentId(file);
        ref.setLocation(json.write(location));
        return ref;
    }

    /**
     * 固定人工选择的结构字段及所在快照。
     */
    private GovernanceEvidencePO structure(Source source, Context context, GovernanceStructureRefDTO input) {
        content.requirePath(context.target().isColumn(), input.getContentPath());
        long object = Ids.parse(input.getObjectId());
        var target = catalog.current(source, object);
        if (target == null || target.retired() || catalog.pending(source.id(), object) > 0) {
            // 其他来源、待核实对象或不存在的对象不进入依据。
            throw BizIllegalException.missing();
        }
        Long column = input.getColumnId() == null ? null : Ids.parse(input.getColumnId());
        var field = column == null ? null : target.column(column);
        if (column != null && field == null) {
            // 字段必须属于此固定对象修订。
            throw BizIllegalException.missing();
        }
        var ref = base(source.id(), input.getContentPath(), "STRUCTURE",
                List.of(object, target.observation().revisionId(), column == null ? 0L : column,
                        field == null ? 0L : field.revisionId()));
        ref.setObjectId(object);
        ref.setObjectRevisionId(target.observation().revisionId());
        ref.setSnapshotId(target.observation().snapshotId());
        ref.setColumnId(column);
        ref.setColumnRevisionId(field == null ? null : field.revisionId());
        return ref;
    }

    /**
     * 人工引用具体资料时检查原文读取资格、所属资料及实际固定位置。
     */
    private GovernanceEvidencePO manual(Source source, Context context, GovernanceManualEvidenceDTO input) {
        content.requirePath(context.target().isColumn(), input.getContentPath());
        if (input.getManualBasis() != null && !input.getManualBasis().isBlank()) {
            // 人工说明是独立证据类型，不能同时声明已引用原文件。
            if (input.getNoteId() != null || input.getRevisionId() != null || input.getLocation() != null
                    || input.getAttachmentId() != null) {
                throw BizIllegalException.invalid("人工依据与资料引用需分别填写");
            }
            var ref = base(source.id(), input.getContentPath(), "MANUAL", input.getManualBasis().strip());
            ref.setManualBasis(input.getManualBasis().strip());
            return ref;
        }
        permissions.require("business-notes.view");
        if (input.getNoteId() == null || input.getRevisionId() == null || input.getLocation() == null) {
            // 资料选择必须有实际定位，不接受仅凭标题的引用。
            throw BizIllegalException.invalid("请指定确切资料修订和来源位置");
        }
        long id = Ids.parse(input.getNoteId());
        long revision = Ids.parse(input.getRevisionId());
        Long file = input.getAttachmentId() == null ? null : Ids.parse(input.getAttachmentId());
        var location = input.getLocation();
        var original = notes.revision(source.id(), id, revision);
        if (file == null) {
            // 以原文中确实存在的范围作为可回看的出处。
            excerpt(original.body(), location);
        } else if (!"ATTACHMENT".equals(location.getSection()) || location.getStart() != null || location.getEnd() != null
                || original.files().stream().noneMatch(attachment -> attachment.id() == file)) {
            // 手工引用原件需命中本修订已存在的成员。
            throw BizIllegalException.invalid("附件不属于该资料修订或位置无效");
        }

        var ref = base(source.id(), input.getContentPath(), "NOTE",
                List.of(id, revision, file == null ? 0L : file, location));
        ref.setNoteId(id);
        ref.setNoteRevisionId(revision);
        ref.setLocation(json.write(location));
        ref.setAttachmentId(file);
        verifyNote(ref);
        return ref;
    }

    /**
     * 标识实际发生的资料证据变更，不原地换成最新资料。
     */
    private Note verifyNote(GovernanceEvidencePO reference) {
        var former = notes.revision(reference.getSourceId(), reference.getNoteId(), reference.getNoteRevisionId());
        var current = notes.current(reference.getSourceId(), reference.getNoteId());
        if (!applies(reference, former, current)) {
            // 提交者应重新选择证据或补充真实的人工依据。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "资料依据已变化，请核对并更新引用");
        }
        return former;
    }

    /**
     * 安全定位原始正文选段，偏移采用浏览器和 Java 一致的 UTF-16 字符坐标。
     */
    private String excerpt(String body, EvidenceLocationDTO location) {
        if (!"BODY".equals(location.getSection()) || location.getStart() == null || location.getEnd() == null
                || location.getStart() < 0 || location.getEnd() > body.length() || location.getEnd() <= location.getStart()) {
            // 任何不合法范围均作为可纠正的请求错误。
            throw BizIllegalException.invalid("引用段落不属于该资料修订");
        }
        String selected = body.substring(location.getStart(), location.getEnd());
        if (selected.isBlank()) {
            // 空行不能成为业务结论的证据。
            throw BizIllegalException.invalid("请引用有内容的资料段落");
        }
        return selected;
    }

    /**
     * 对外记录使用定位摘要，避免在索引和日志中暴露依据原文。
     */
    private GovernanceEvidencePO base(long source, String path, String kind, Object location) {
        var ref = new GovernanceEvidencePO();
        ref.setSourceId(source);
        ref.setContentPath(path);
        ref.setKind(kind);
        ref.setLocatorKey(HexFormat.of().formatHex(secrets.hmac("governance:evidence|" + kind + "|" + json.write(location))));
        return ref;
    }
}
