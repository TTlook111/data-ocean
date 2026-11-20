package com.dataocean.module.governance.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 持久化候选固定身份、关联对象与逐次审核修订。
 */
public interface GovernanceDiscoveryMapper {

    /**
     * 候选当前修订的完整投影，JSON 在业务层按明确类型读取。
     */
    String CURRENT =
            """
            SELECT c.id,c.request_id,c.source_id,c.kind,c.snapshot_id,c.evidence,
                   c.version,r.state,r.content,r.dependencies,
                   EXISTS(SELECT 1 FROM gov_generation_input i
                          WHERE i.request_id=c.request_id AND i.state='USED') AS used_business_notes
            FROM gov_discovery_candidate c JOIN gov_discovery_revision r
              ON r.candidate_id=c.id AND r.version=c.version
            """;

    /**
     * @param id 稳定候选 ID
     * @param requestId 原发现任务
     * @param sourceId 归属源
     * @param kind 关联或指标
     * @param snapshotId 发现所用目录
     * @param evidence 原发现确切依据
     * @param version 当前草稿或审核版本
     * @param state 当前状态
     * @param content 类型化定义内容
     * @param dependencies 审核时固定的版本
     * @param usedBusinessNotes 原任务是否实际将业务资料送入模型
     */
    record Candidate(
            long id,
            String requestId,
            long sourceId,
            String kind,
            long snapshotId,
            String evidence,
            int version,
            String state,
            String content,
            String dependencies,
            boolean usedBusinessNotes) {}

    /**
     * 对同源表的候选作稳定分页，关联两端读取同一条记录。
     */
    @Select(
            CURRENT
                    + """
            JOIN gov_discovery_object o ON o.candidate_id=c.id
            WHERE c.source_id=#{source} AND o.object_id=#{object} AND c.kind=#{kind}
            ORDER BY c.id
            """)
    Page<Candidate> list(
            Page<Candidate> page,
            @Param("source") long source,
            @Param("object") long object,
            @Param("kind") String kind);

    /**
     * 源锁内只允许办理当前页面确切归属的候选。
     */
    @Select(
            CURRENT
                    + """
            JOIN gov_discovery_object o ON o.candidate_id=c.id
            WHERE c.source_id=#{source} AND o.object_id=#{object} AND c.id=#{id}
            FOR UPDATE
            """)
    Candidate lock(@Param("source") long source, @Param("object") long object, @Param("id") long id);

    /**
     * 读取同一发现身份的固定依据，用于判定能否复用原候选。
     */
    @Select(
            """
            SELECT evidence FROM gov_discovery_candidate
            WHERE source_id=#{source} AND kind=#{kind} AND identity_key=#{identity}
            """)
    String evidenceForIdentity(
            @Param("source") long source, @Param("kind") String kind, @Param("identity") String identity);

    /**
     * 保存可追溯的原始发现。
     */
    @Insert(
            """
            INSERT INTO gov_discovery_candidate(id,request_id,source_id,object_id,kind,identity_key,snapshot_id,evidence)
            VALUES(#{id},#{request},#{source},#{object},#{kind},#{identity},#{snapshot},#{evidence})
            """)
    void add(
            @Param("id") long id,
            @Param("request") String request,
            @Param("source") long source,
            @Param("object") long object,
            @Param("kind") String kind,
            @Param("identity") String identity,
            @Param("snapshot") long snapshot,
            @Param("evidence") String evidence);

    /**
     * 将各端实际表绑定到同一候选。
     */
    @Insert("INSERT INTO gov_discovery_object (candidate_id,object_id) VALUES(#{candidate},#{object})")
    void attach(@Param("candidate") long candidate, @Param("object") long object);

    /**
     * 保存生成、人工作业或确认形成的独立修订。
     */
    @Insert(
            """
            INSERT INTO gov_discovery_revision(id,candidate_id,version,state,content,dependencies,actor_id)
            VALUES(#{id},#{candidate},#{version},#{state},#{content},#{dependencies},#{actor})
            """)
    void revise(
            @Param("id") long id,
            @Param("candidate") long candidate,
            @Param("version") int version,
            @Param("state") String state,
            @Param("content") String content,
            @Param("dependencies") String dependencies,
            @Param("actor") long actor);

    /**
     * 推进当前指针，旧版本不能覆盖人工内容。
     */
    @Update("UPDATE gov_discovery_candidate SET version=version+1 WHERE id=#{id} AND version=#{version}")
    int promote(@Param("id") long id, @Param("version") int version);
}
