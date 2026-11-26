package com.dataocean.module.feedback.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 处理结果与复验保存各自的不可变版本。 */
public interface FeedbackResolutionMapper {

    /**
     * 已提交的处理结果，按处理路径保留适用的发布或实际修正责任。
     */
    record ResultRecord(
            long issueId,
            int version,
            long authorId,
            String impact,
            String summary,
            String checkMethod,
            String referenceText,
            String regression,
            LocalDateTime submittedAt,
            Long knowledgeBindingId,
            /**
             * 源系统实际修正人或团队，其他处理路径为空。
             */
            String sourceRepairer) {}

    /** 人工复验绑定当次处理结果、本人身份及有效授权摘要。 */
    record Verification(
            long issueId,
            int version,
            int resultVersion,
            long verifierId,
            String contextSignature,
            String decision,
            String testScope,
            String expected,
            String actual,
            String dataBasis,
            LocalDateTime verifiedAt,
            String recheckRequestId) {}

    /** 只读取事项当前指向的结果，不用最新时间猜测有效版本。 */
    @Select("SELECT * FROM fb_issue_result WHERE issue_id=#{issue} AND version=#{version}")
    ResultRecord result(long issue, int version);

    /** 查看确切人工复验修订。 */
    @Select("SELECT * FROM fb_issue_verification WHERE issue_id=#{issue} AND version=#{version}")
    Verification verification(long issue, int version);

    /** 原处理结果在重试或转派后仍能按版本回查。 */
    @Insert(
            """
            INSERT INTO fb_issue_result(issue_id,version,author_id,impact,summary,check_method,reference_text,regression,
                knowledge_binding_id,source_repairer)
            VALUES(#{issueId},#{version},#{authorId},#{impact},#{summary},#{checkMethod},#{referenceText},#{regression},
                #{knowledgeBindingId},#{sourceRepairer})
            """)
    void appendResult(ResultRecord record);

    /** 每次人工复验追加记录并与事实结果同时提交。 */
    @Insert(
            """
            INSERT INTO fb_issue_verification(issue_id,version,result_version,verifier_id,context_signature,
                decision,test_scope,expected,actual,data_basis,recheck_request_id)
            VALUES(#{issueId},#{version},#{resultVersion},#{verifierId},#{contextSignature},
                #{decision},#{testScope},#{expected},#{actual},#{dataBasis},#{recheckRequestId})
            """)
    void appendVerification(Verification record);

    /** 提交修复推进待验证，旧复验不能用于新处理结果。 */
    @Update(
            """
            UPDATE fb_issue SET status='PENDING_VERIFICATION', result_version=#{next},verification_version=NULL,
                version=#{next},updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{issue} AND version=#{current}
            """)
    int submit(long issue, int current, int next);

    /** 通过后仍需结案，不通过回到处理中等待补正。 */
    @Update(
            """
            UPDATE fb_issue SET status=#{status},verification_version=#{next},version=#{next},updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{issue} AND version=#{current}
            """)
    int verify(long issue, int current, int next, String status);

    /** 结案仅在当前事项锁和有效复验条件内完成。 */
    @Update(
            """
            UPDATE fb_issue SET status=#{status},version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{issue} AND version=#{current}
            """)
    int close(long issue, int current, String status);
}
