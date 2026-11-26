package com.dataocean.module.governance.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 记录真实读取轮次、预算消耗和非敏感回执。
 */
public interface GovernanceSampleMapper {

    /**
     * 回查读取轮次，用于预算和失败接管判断。
     */
    @Select(
            """
            SELECT attempt,status,returned_rows,reason,statement_sql
            FROM gov_sample_attempt WHERE request_id=#{request} ORDER BY attempt
            """)
    List<Attempt> attempts(String request);

    /**
     * 实际访问业务源前持久保留一次尝试。
     */
    @Insert(
            """
            INSERT INTO gov_sample_attempt(request_id,attempt,field_id,reason,statement_sql,status)
            VALUES(#{request},#{attempt},#{field},#{reason},#{sql},'RUNNING')
            """)
    void reserve(
            @Param("request") String request,
            @Param("attempt") int attempt,
            @Param("field") long field,
            @Param("reason") String reason,
            @Param("sql") String sql);

    /**
     * 只将真实完成或失败的执行更新为终态。
     */
    @Update(
            """
            UPDATE gov_sample_attempt SET status=#{status},returned_rows=#{rows},ended_at=UTC_TIMESTAMP(3)
            WHERE request_id=#{request} AND attempt=#{attempt} AND status='RUNNING'
            """)
    int finish(
            @Param("request") String request,
            @Param("attempt") int attempt,
            @Param("status") String status,
            @Param("rows") int rows);

    /**
     * 结果也受实时来源权限约束，曾取得的样本不得在撤权后继续使用。
     */
    @Select("SELECT EXISTS(SELECT 1 FROM gov_sample_attempt WHERE request_id=#{request} AND returned_rows > 0)")
    boolean used(String request);

    /**
     * @param attempt 实际访问尝试次序
     * @param status 运行、成功、失败或拒绝
     * @param returnedRows 从源端实际返回的行数
     * @param reason 需要辅助读取的缺口类型
     * @param statementSql 固定的带参数只读语句
     */
    record Attempt(int attempt, String status, int returnedRows, String reason, String statementSql) {}
}
