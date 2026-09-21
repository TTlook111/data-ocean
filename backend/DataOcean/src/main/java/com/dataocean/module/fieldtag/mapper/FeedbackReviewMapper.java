package com.dataocean.module.fieldtag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.fieldtag.entity.FeedbackReview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Collection;

/**
 * 反馈审核 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供反馈审核的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface FeedbackReviewMapper extends BaseMapper<FeedbackReview> {

    /**
     * 待审反馈按负责源下推。归属优先字段 {@code db_column_meta.datasource_id}，
     * 没有字段时回落查询任务 {@code query_task.datasource_id}。
     */
    @Select("""
            <script>
            SELECT r.*
            FROM feedback_review r
            INNER JOIN user_feedback f ON f.id = r.feedback_id
            LEFT JOIN db_column_meta c ON c.id = f.column_meta_id
            LEFT JOIN query_task t ON t.id = f.query_task_id
            WHERE r.review_status = #{status}
              AND (
                    (f.column_meta_id IS NOT NULL AND c.datasource_id IN
                        <foreach collection="datasourceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                    )
                    OR
                    (f.column_meta_id IS NULL AND t.datasource_id IN
                        <foreach collection="datasourceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                    )
                  )
            ORDER BY r.id
            </script>
            """)
    IPage<FeedbackReview> selectPendingInDatasources(Page<FeedbackReview> page,
                                                     @Param("status") String status,
                                                     @Param("datasourceIds") Collection<Long> datasourceIds);
}
