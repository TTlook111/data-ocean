package com.dataocean.module.knowledge.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 保存一次业务发布的真实范围与对原批次的整个回退。
 */
public interface KnowledgePublicationBatchMapper {

    /**
     * @param id 原批次编号
     * @param verifiedAt 整批正式发布核实时间
     */
    record Batch(long id, LocalDateTime verifiedAt) {}

    /**
     * @param id 原回退办理编号
     * @param actorId 确认回退的实际操作者
     * @param reason 此次业务回退依据
     * @param createdAt 已提交切换时间
     * @param verifiedAt 实际核实完成时间
     */
    record Rollback(long id, long actorId, String reason, LocalDateTime createdAt, LocalDateTime verifiedAt) {}

    /**
     * 新发布批次同实际指针事务保存。
     */
    @Insert(
            """
            INSERT INTO kn_publication_batch(id,source_id,actor_id,command_id)
            VALUES(#{id},#{source},#{actor},#{command})
            """)
    void create(
            @Param("id") long id,
            @Param("source") long source,
            @Param("actor") long actor,
            @Param("command") String command);

    /**
     * 只固定已写入的实际成员。
     */
    @Insert("INSERT INTO kn_publication_batch_member(binding_id,batch_id) VALUES(#{binding},#{batch})")
    void add(@Param("batch") long batch, @Param("binding") long binding);

    /**
     * 任一成员均可定位原发布范围。
     */
    @Select("SELECT batch_id FROM kn_publication_batch_member WHERE binding_id=#{binding}")
    Long forBinding(long binding);

    /**
     * 读取当前源的原发布批次。
     */
    @Select("SELECT id,verified_at FROM kn_publication_batch WHERE source_id=#{source} AND id=#{batch}")
    Batch get(@Param("source") long source, @Param("batch") long batch);

    /**
     * 按同一发布顺序读回实际冻结成员。
     */
    @Select("SELECT binding_id FROM kn_publication_batch_member WHERE batch_id=#{batch} ORDER BY binding_id")
    List<Long> members(long batch);

    /**
     * 所有成员实际索引和指针通过后才写核实时间。
     */
    @Update("UPDATE kn_publication_batch SET verified_at=UTC_TIMESTAMP(3) WHERE id=#{batch} AND verified_at IS NULL")
    int verify(long batch);

    /**
     * 同一批次只记录一次正式回退。
     */
    @Insert(
            """
            INSERT INTO kn_publication_rollback(id,batch_id,actor_id,command_id,reason)
            VALUES(#{id},#{batch},#{actor},#{command},#{reason})
            """)
    void rollback(
            @Param("id") long id,
            @Param("batch") long batch,
            @Param("actor") long actor,
            @Param("command") String command,
            @Param("reason") String reason);

    /**
     * 读取原办理的切换、依据和已核实证明。
     */
    @Select(
            """
            SELECT id,actor_id,reason,created_at,verified_at FROM kn_publication_rollback
            WHERE batch_id=#{batch}
            """)
    Rollback rolled(long batch);

    /**
     * 原问题版本和已恢复的上一版均可回看最近一次实际回退。
     */
    @Select(
            """
            SELECT r.batch_id FROM kn_publication_rollback r
            JOIN kn_publication_batch p ON p.id=r.batch_id
            JOIN kn_publication_batch_member m ON m.batch_id=r.batch_id
            JOIN kn_release_binding b ON b.id=m.binding_id
            WHERE p.source_id=#{source} AND (b.id=#{binding} OR b.previous_binding_id=#{binding})
            ORDER BY r.id DESC LIMIT 1
            """)
    Long latestRollbackBatch(@Param("source") long source, @Param("binding") long binding);

    /**
     * 核实只完成原回退，不再改动指针。
     */
    @Update("UPDATE kn_publication_rollback SET verified_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND verified_at IS NULL")
    int verifyRollback(long id);

    /**
     * 回退结果尚未核实时不让其他命令覆盖、不放行普通问数。
     */
    @Select(
            """
            SELECT EXISTS (
              SELECT 1 FROM kn_publication_rollback r
              JOIN kn_publication_batch_member m ON m.batch_id=r.batch_id
              JOIN kn_release_binding b ON b.id=m.binding_id
              WHERE b.scope_id=#{scope} AND r.verified_at IS NULL)
            """)
    boolean rollbackPending(long scope);
}
