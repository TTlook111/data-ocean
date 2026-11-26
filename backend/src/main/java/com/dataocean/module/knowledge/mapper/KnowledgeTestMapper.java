package com.dataocean.module.knowledge.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 维护稳定题目、已保存修订及审核记录。
 */
public interface KnowledgeTestMapper {

    /**
     * 当前题目状态和对应文档实际确认在同一查询中定位。
     */
    String CURRENT =
            """
            SELECT t.id,t.document_id,t.question,t.version,t.head_revision_id,t.reviewed_revision_id,
                   r.revision AS number,r.document_revision_id,d.confirmed_revision_id,r.content
            FROM kn_test_case t JOIN kn_test_case_revision r ON r.id=t.head_revision_id
              JOIN kn_document d ON d.id=t.document_id
            """;

    /**
     * 当前题目、内容和文档确认定位。
     */
    record Test(
            long id,
            long documentId,
            String question,
            int version,
            long headRevisionId,
            Long reviewedRevisionId,
            int number,
            long documentRevisionId,
            Long confirmedRevisionId,
            String content) {}

    /**
     * 当前审核题的不可变修订和判定正文，供预检及候选共用。
     */
    record ReviewedTest(long revisionId, String content) {}

    /**
     * 同源内分页，另一来源的数据不可混入。
     */
    @Select(
            CURRENT
                    + """
            WHERE t.source_id=#{source} AND (#{document} IS NULL OR t.document_id=#{document})
            ORDER BY t.id DESC
            """)
    Page<Test> list(Page<Test> page, @Param("source") long source, @Param("document") Long document);

    /**
     * 发布准备只使用同一文档确认下本题的当前审核内容。
     */
    @Select(
            """
            SELECT r.id AS revision_id,r.content FROM kn_test_case t JOIN kn_test_case_revision r ON r.id=t.head_revision_id
            JOIN kn_test_review a ON a.revision_id=r.id AND a.test_case_id=t.id
            WHERE t.source_id=#{source} AND t.document_id=#{document}
              AND t.reviewed_revision_id=r.id AND r.document_revision_id=#{revision}
            ORDER BY t.id
            """)
    List<ReviewedTest> reviewed(
            @Param("source") long source, @Param("document") long document, @Param("revision") long revision);

    /**
     * 获取当前实际待审修订。
     */
    @Select(CURRENT + " WHERE t.source_id=#{source} AND t.id=#{id}")
    Test get(@Param("source") long source, @Param("id") long id);

    /**
     * 提供近期题干用于减少重复建议，期望、执行条件和样例不外发。
     */
    @Select("""
            SELECT question FROM kn_test_case WHERE source_id=#{source} AND document_id=#{document}
            ORDER BY id DESC LIMIT 60
            """)
    List<String> recentQuestions(long source, long document);

    /**
     * 检查同一文档的题干，锁定读取可见同源刚刚提交的采纳结果。
     */
    @Select("""
            SELECT id FROM kn_test_case WHERE source_id=#{source} AND document_id=#{document}
                AND LOWER(question)=LOWER(#{question}) ORDER BY id LIMIT 1 FOR UPDATE
            """)
    Long sameQuestion(long source, long document, String question);

    /**
     * 保存稳定身份和首份题目。
     */
    @Insert(
            """
            INSERT INTO kn_test_case (id,source_id,document_id,question,head_revision_id)
            VALUES (#{id},#{source},#{document},#{question},#{revision})
            """)
    void create(
            @Param("id") long id,
            @Param("source") long source,
            @Param("document") long document,
            @Param("question") String question,
            @Param("revision") long revision);

    /**
     * 保存不可变题单修订。
     */
    @Insert(
            """
            INSERT INTO kn_test_case_revision (id,test_case_id,document_revision_id,revision,content,created_by)
            VALUES (#{revision},#{id},#{basis},#{number},#{content},#{actor})
            """)
    void append(
            @Param("revision") long revision,
            @Param("id") long id,
            @Param("basis") long basis,
            @Param("number") int number,
            @Param("content") String content,
            @Param("actor") long actor);

    /**
     * 编辑以实际读到的版本推进。
     */
    @Update(
            """
            UPDATE kn_test_case SET question=#{question},head_revision_id=#{revision},version=version+1
            WHERE source_id=#{source} AND id=#{id} AND version=#{version}
            """)
    int advance(
            @Param("source") long source,
            @Param("id") long id,
            @Param("version") int version,
            @Param("question") String question,
            @Param("revision") long revision);

    /**
     * 审核固定实际已保存的正文。
     */
    @Update(
            """
            UPDATE kn_test_case SET reviewed_revision_id=#{revision},version=version+1
            WHERE source_id=#{source} AND id=#{id} AND version=#{version} AND head_revision_id=#{revision}
            """)
    int review(
            @Param("source") long source,
            @Param("id") long id,
            @Param("version") int version,
            @Param("revision") long revision);

    /**
     * 实际验证身份固定，任务接收时还要复核当前源权限。
     */
    @Insert(
            """
            INSERT INTO kn_test_review (test_case_id,revision_id,reviewed_by,executor_id)
            VALUES (#{id},#{revision},#{actor},#{actor})
            """)
    void recordReview(@Param("id") long id, @Param("revision") long revision, @Param("actor") long actor);
}
