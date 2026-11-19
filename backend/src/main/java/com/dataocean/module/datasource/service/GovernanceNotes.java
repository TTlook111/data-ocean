package com.dataocean.module.datasource.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.module.datasource.entity.po.BusinessNotePO;
import com.dataocean.module.datasource.entity.po.BusinessNoteRevisionPO;
import com.dataocean.module.datasource.mapper.BusinessNoteMapper;
import com.dataocean.module.datasource.mapper.BusinessNoteReadMapper;
import com.dataocean.module.datasource.mapper.BusinessNoteRevisionMapper;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 为获准的同源治理任务及实际证据提供固定资料修订，原件读取仍由资料模块控制。
 */
@Service
@RequiredArgsConstructor
public class GovernanceNotes {

    /**
     * 读取当前状态及资料与源的对应关系。
     */
    private final BusinessNoteMapper notes;

    /**
     * 读取不可变正文修订。
     */
    private final BusinessNoteRevisionMapper revisions;

    /**
     * 查找确切修订的附件成员并登记防删除引用。
     */
    private final BusinessNoteReadMapper reads;

    /**
     * 为预览或受控任务列出当前同源的有效修订，按资料身份稳定排序。
     */
    public List<Note> active(long source) {
        return notes.selectList(Wrappers.<BusinessNotePO>lambdaQuery()
                        .eq(BusinessNotePO::getSourceId, source)
                        .eq(BusinessNotePO::getState, "ACTIVE")
                        .orderByAsc(BusinessNotePO::getId))
                .stream().map(note -> current(source, note.getId())).toList();
    }

    /**
     * 读取稳定资料当前的有效修订，吞吐限制由取用任务按给定预算执行。
     */
    public Note current(long source, long id) {
        var note = owned(source, id);
        if (!"ACTIVE".equals(note.getState())) {
            // 撤下的原文只作为历史证据回看。
            throw stale();
        }
        var revision = revisions.selectOne(Wrappers.<BusinessNoteRevisionPO>lambdaQuery()
                .eq(BusinessNoteRevisionPO::getNoteId, id)
                .eq(BusinessNoteRevisionPO::getRevision, note.getRevision()));
        return view(revision);
    }

    /**
     * 沿固定编号读取已确认属于同一来源的历史修订。
     */
    public Note revision(long source, long id, long revisionId) {
        owned(source, id);
        var revision = revisions.selectById(revisionId);
        if (revision == null || revision.getNoteId() != id) {
            // 另一份资料上的修订不能挂到本条依据。
            throw BizIllegalException.missing();
        }
        return view(revision);
    }

    /**
     * 固定确切修订下的有效原件，调用方需先检查目的地和当前任务资格。
     */
    public byte[] attachment(Note note, long file) {
        var attachment = reads.download(note.noteId(), note.revision(), file);
        if (attachment == null) {
            // 不允许从其他修订复用未登记的原件。
            throw BizIllegalException.missing();
        }
        return attachment.getContent();
    }

    /**
     * 源锁已经由调用者获取，与取消、换版、撤下和删除使用一致顺序。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reference(long source, Note note, String kind, String target) {
        // 调用者须已核对实际取用范围或被引用片段的等价性。
        current(source, note.noteId());
        revision(source, note.noteId(), note.revisionId());
        reads.reference(note.noteId(), note.revisionId(), kind, target);
    }

    /**
     * 校验同源身份，阻止通过备注、修订或附件编号串源。
     */
    private BusinessNotePO owned(long source, long id) {
        var note = notes.selectById(id);
        if (note == null || note.getSourceId() != source || "DELETED".equals(note.getState())) {
            // 不披露其他来源的资料是否存在。
            throw BizIllegalException.missing();
        }
        return note;
    }

    /**
     * 将正文与当版附件摘要组成同一份可定位输入。
     */
    private Note view(BusinessNoteRevisionPO revision) {
        if (revision == null) {
            // 需要由权威资料事实提供内容。
            throw BizIllegalException.missing();
        }
        var attachments = reads.attachments(revision.getId()).stream()
                .map(file -> new File(file.getId(), file.getName(), file.getDigest(), file.getByteSize())).toList();
        return new Note(revision.getNoteId(), revision.getId(), revision.getRevision(),
                revision.getTitle(), revision.getBody(), attachments);
    }

    /**
     * 在提交锁内通知调用方重新核对来源。
     */
    private BizIllegalException stale() {
        return new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "业务说明已变化，请重新核对");
    }

    /**
     * 资料附件的不可变索引。
     *
     * @param id 原件编号
     * @param name 安全原文件名
     * @param digest 原件内容摘要
     * @param byteSize 实际原件大小，用于限制任务的解析输入
     */
    public record File(long id, String name, String digest, int byteSize) {}

    /**
     * 只能作为受控输入或证据使用的资料修订。
     *
     * @param noteId 资料稳定编号
     * @param revisionId 资料修订主键
     * @param revision 供用户回看的修订序号
     * @param title 当时实际记录的标题
     * @param body 受限的正文
     * @param files 该修订的实际原件成员
     */
    public record Note(long noteId, long revisionId, int revision, String title, String body, List<File> files) {

        /**
         * 标题及原件编号不参与实质内容判断，保留文件摘要的重数。
         */
        public boolean equivalent(Note other) {
            return body.equals(other.body)
                    && files.stream().map(File::digest).sorted().toList()
                            .equals(other.files.stream().map(File::digest).sorted().toList());
        }
    }
}
