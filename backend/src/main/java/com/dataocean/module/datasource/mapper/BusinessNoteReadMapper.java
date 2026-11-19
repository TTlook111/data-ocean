package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.datasource.entity.po.BusinessNotePO;
import com.dataocean.module.datasource.entity.po.NoteAttachmentPO;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 按资料、修订与管理员当前查询范围访问说明和原文件。
 */
public interface BusinessNoteReadMapper {

    /**
     * 同一关联查询使用提交人、源和标题的组合筛选。
     */
    @Select(
            """
            <script>
            SELECT n.* FROM ds_business_note n JOIN iam_user u ON u.id=n.author_id WHERE n.state!='DELETED'
            <if test="source != null">AND n.source_id=#{source}</if>
            <if test="state != null and !state.isEmpty()">AND n.state=#{state}</if>
            <if test="title != null">AND LOWER(n.title) LIKE #{title} ESCAPE '!'</if>
            <if test="author != null">AND (LOWER(u.display_name) LIKE #{author} ESCAPE '!' OR LOWER(u.username) LIKE #{author} ESCAPE '!')</if>
            </script>
            """)
    Page<BusinessNotePO> page(
            Page<BusinessNotePO> page,
            @Param("source") Long source,
            @Param("state") String state,
            @Param("title") String title,
            @Param("author") String author);

    /**
     * 只读取该版附件名称，不在普通查询中读取字节。
     */
    @Select(
            """
            SELECT a.id,a.note_id,a.name,a.byte_size,a.digest
            FROM ds_business_revision_attachment link JOIN ds_business_attachment a ON a.id=link.attachment_id
            WHERE link.revision_id=#{revision} ORDER BY a.id
            """)
    List<NoteAttachmentPO> attachments(long revision);

    /**
     * 只读取所选资料的确切修订所引用的附件，杜绝猜附件编号下载。
     */
    @Select(
            """
            SELECT a.* FROM ds_business_attachment a
            JOIN ds_business_revision_attachment link ON link.note_id=a.note_id AND link.attachment_id=a.id
            JOIN ds_business_note_revision r ON r.id=link.revision_id AND r.note_id=link.note_id
            WHERE a.note_id=#{note} AND r.revision=#{revision} AND a.id=#{attachment}
            """)
    NoteAttachmentPO download(
            @Param("note") long note, @Param("revision") int revision, @Param("attachment") long attachment);

    /**
     * 关联新修订所保留或新增的文件，原文件与同源历史保留。
     */
    @Insert(
            "INSERT INTO ds_business_revision_attachment(note_id,revision_id,attachment_id) VALUES(#{note},#{revision},#{attachment})")
    void link(@Param("note") long note, @Param("revision") long revision, @Param("attachment") long attachment);

    /**
     * 检查资料任意修订的全部实际引用数量。
     */
    @Select("SELECT COUNT(*) FROM ds_business_note_reference WHERE note_id=#{note}")
    int references(long note);

    /**
     * 资料变更时间采用平台库的 UTC 时钟。
     */
    @Select("SELECT UTC_TIMESTAMP(3)")
    LocalDateTime now();
}
