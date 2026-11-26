package com.dataocean.module.datasource.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 持久化尚未提交的上传和需要补做的 OSS 删除。
 */
public interface NoteAttachmentCleanupMapper {

    /**
     * 在上传前提交预留，给网络请求一个有界的回收宽限期。
     */
    @Insert(
            """
            INSERT INTO ds_business_attachment_cleanup(object_bucket,object_key,ready_at)
            VALUES(#{bucket},#{key},UTC_TIMESTAMP(3) + INTERVAL 10 MINUTE)
            """)
    void reserveUpload(@Param("bucket") String bucket, @Param("key") String objectKey);

    /**
     * 锁定本次上传的预留，禁止网络写入与回收同一对象并发。
     */
    @Select(
            """
            SELECT object_key FROM ds_business_attachment_cleanup
            WHERE object_bucket=#{bucket} AND object_key=#{key} FOR UPDATE
            """)
    String lockUpload(@Param("bucket") String bucket, @Param("key") String objectKey);

    /**
     * 永久删除资料时将其原件逐一登记为待清理对象。
     */
    @Insert(
            """
            INSERT INTO ds_business_attachment_cleanup(object_bucket,object_key,ready_at)
            SELECT object_bucket,object_key,UTC_TIMESTAMP(3) FROM ds_business_attachment WHERE note_id=#{note}
            """)
    void queueNote(long note);

    /**
     * 领取当前 Bucket 中已到期且未被其他事务占用的对象。
     */
    @Select(
            """
            SELECT object_key FROM ds_business_attachment_cleanup
            WHERE object_bucket=#{bucket} AND ready_at<=UTC_TIMESTAMP(3)
            ORDER BY ready_at,object_key LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    String next(String bucket);

    /**
     * 成功上传的业务事务或成功清理的任务移除各自的待处理记录。
     */
    @Delete("DELETE FROM ds_business_attachment_cleanup WHERE object_bucket=#{bucket} AND object_key=#{key}")
    void complete(@Param("bucket") String bucket, @Param("key") String objectKey);

    /**
     * 清理失败时保留任务并延后再次尝试，避免阻塞其他对象。
     */
    @Update(
            """
            UPDATE ds_business_attachment_cleanup SET ready_at=UTC_TIMESTAMP(3) + INTERVAL 1 MINUTE
            WHERE object_bucket=#{bucket} AND object_key=#{key}
            """)
    void retry(@Param("bucket") String bucket, @Param("key") String objectKey);
}
