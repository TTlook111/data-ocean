package com.dataocean.module.access.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.access.entity.po.AccessGrantPO;
import com.dataocean.module.access.entity.po.AccessRequestPO;
import com.dataocean.module.access.entity.po.DepartmentSourcePO;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.iam.entity.po.UserPO;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 访问授权涉及人员、部门和数据源的关联读取及历史写入。
 */
public interface AccessReadMapper {

    /**
     * 部门和成员关系的交集历史，用于真实来源而非推断角色授权。
     *
     * @param configurationId 部门配置历史编号
     * @param membershipId 直属关系历史编号
     * @param departmentId 授予的部门
     * @param departmentName 部门当前名称
     * @param userId 实际被授权人员
     * @param sourceId 数据源
     * @param startedAt 同时生效的时间
     * @param endedAt 较早结束时间
     * @param state 当前可用性
     * @param ruleSnapshot 留存配置时的规则
     */
    record DepartmentGrantRow(
            long configurationId,
            long membershipId,
            long departmentId,
            String departmentName,
            long userId,
            long sourceId,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            String state,
            String ruleSnapshot) {}

    /**
     * 本人可回看某次单据实际提交的修订。
     *
     * @param version 原业务版本
     * @param action 实际持久化动作
     * @param actorId 实际经办身份
     * @param createdAt 数据库 UTC 时刻
     */
    record RequestActivityRow(int version, String action, long actorId, LocalDateTime createdAt) {}

    /**
     * 统一授权目录的分页投影，保留装配两类来源需要的事实。
     *
     * @param kind 申请或部门来源
     * @param recordId 授权或部门配置编号
     * @param membershipId 部门成员关系编号，个人来源为零
     * @param departmentId 组织来源所属部门
     * @param departmentName 组织来源名称
     * @param userId 实际被授权人
     * @param sourceId 数据源
     * @param startedAt 实际生效区间起点
     * @param endedAt 来源在平台的结束时刻
     * @param state 用于筛选的实际状态
     * @param ruleSnapshot 部门来源原规则快照
     */
    record GrantDirectoryRow(
            String kind,
            long recordId,
            long membershipId,
            Long departmentId,
            String departmentName,
            long userId,
            long sourceId,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            String state,
            String ruleSnapshot) {}

    /**
     * 在 SQL 内按实际账号状态及当前功能、页面权限筛出可办理人员。
     */
    String ELIGIBLE =
            """
            SELECT u.id FROM iam_user u
            JOIN iam_user_role ur ON ur.user_id=u.id AND ur.ended_at IS NULL
            JOIN iam_role rr ON rr.id=ur.role_id AND rr.lifecycle='ENABLED'
            JOIN iam_role_permission rp ON rp.role_id=rr.id
            JOIN iam_permission p ON p.code=rp.permission_code AND p.enabled=1
            WHERE u.lifecycle='ENABLED' AND u.must_change_password=0
              AND p.code IN ('access-requests.review','access-requests.view')
            GROUP BY u.id HAVING COUNT(DISTINCT p.code)=2
            """;

    /**
     * 同一数据源始终按申请人相对责任选择具体办理人。
     */
    String ASSIGNEE = " IF(rs.primary_user_id=r.applicant_id, rs.alternate_user_id, rs.primary_user_id) ";

    /**
     * 联合使用要求全部必要来源持续有效。
     */
    String JOINT_AVAILABLE =
            """
            (r.joint=0 OR NOT EXISTS (
                SELECT 1 FROM access_request_item required_item
                LEFT JOIN ds_source related ON related.id=required_item.source_id
                WHERE required_item.request_id=r.id
                  AND (required_item.state!='EFFECTIVE' OR related.lifecycle IS NULL OR related.lifecycle!='ENABLED')
            ))
            """;

    /**
     * 审批队列先限定可见子项，再用相同口径筛选及分页申请。
     */
    @Select(
            """
            <script>
            SELECT r.* FROM access_request r JOIN iam_user applicant ON applicant.id=r.applicant_id
            WHERE 1=1
            <if test="request != null">AND r.id=#{request}</if>
            <if test="keyword != null">AND LOWER(r.purpose) LIKE #{keyword} ESCAPE '!'</if>
            <if test="applicant != null">AND (LOWER(applicant.display_name) LIKE #{applicant} ESCAPE '!' OR LOWER(applicant.username) LIKE #{applicant} ESCAPE '!')</if>
            <if test="from != null">AND r.submitted_at &gt;= #{from}</if>
            <if test="until != null">AND r.submitted_at &lt; #{until}</if>
            <if test="queue == 'MINE'">AND r.applicant_id=#{actor}</if>
            AND EXISTS (
                SELECT 1 FROM access_request_item i
                LEFT JOIN ds_source s ON s.id=i.source_id
                LEFT JOIN access_responsibility rs ON rs.source_id=i.source_id
                <if test="queue == 'HANDLED'">
                    JOIN access_request_item_action a ON a.item_id=i.id AND a.actor_id=#{actor}
                    <if test="source != null or state != null">
                        LEFT JOIN access_request_revision h ON h.request_id=i.request_id AND h.version=a.decision_version
                        LEFT JOIN JSON_TABLE(h.snapshot, '$.items[*]' COLUMNS (
                            item_id BIGINT UNSIGNED PATH '$.id',
                            source_id BIGINT UNSIGNED PATH '$.source.id',
                            state VARCHAR(24) PATH '$.state'
                        )) earlier ON earlier.item_id=i.id
                    </if>
                </if>
                WHERE i.request_id=r.id
                <if test="source != null">
                    <choose>
                        <when test="queue == 'HANDLED'">
                            AND (CASE WHEN i.handled_by=#{actor} THEN i.source_id ELSE earlier.source_id END)=#{source}
                        </when>
                        <otherwise>AND i.source_id=#{source}</otherwise>
                    </choose>
                </if>
                <if test="state != null">
                    <choose>
                        <when test="queue == 'HANDLED'">
                            AND (CASE WHEN i.handled_by=#{actor} THEN i.state ELSE earlier.state END)=#{state}
                        </when>
                        <when test="queue == 'MINE' and (state == 'ROUTING' or state == 'PENDING')">
                            AND i.state='PENDING'
                            <if test="state == 'PENDING'">AND NOT</if>
                            <if test="state == 'ROUTING'">AND</if>
                            (s.lifecycle IS NULL OR s.lifecycle!='ENABLED'
                             OR
            """
                    + ASSIGNEE + """
                             IS NULL OR
            """ + ASSIGNEE
                    + """
                             NOT IN (""" + ELIGIBLE
                    + """
                            ))
                        </when>
                        <otherwise>AND i.state=#{state}</otherwise>
                    </choose>
                </if>
                <choose>
                    <when test="queue == 'MINE'">AND (r.submitted_at IS NOT NULL OR i.state='DRAFT')</when>
                    <when test="queue == 'PENDING'">
                        AND i.state='PENDING' AND s.lifecycle='ENABLED'
                        AND applicant.lifecycle='ENABLED' AND r.applicant_id != #{actor}
                        AND """
                    + ASSIGNEE
                    + """
                        =#{actor}
                    </when>
                    <when test="queue == 'ROUTING'">
                        AND i.state='PENDING' AND applicant.lifecycle='ENABLED'
                        AND (s.lifecycle IS NULL OR s.lifecycle != 'ENABLED'
                          OR """
                    + ASSIGNEE + """
                            IS NULL OR """ + ASSIGNEE
                    + """
                            NOT IN (""" + ELIGIBLE
                    + """
                        ))
                    </when>
                </choose>
            )
            </script>
            """)
    Page<AccessRequestPO> requests(
            Page<AccessRequestPO> page,
            @Param("actor") long actor,
            @Param("queue") String queue,
            @Param("source") Long source,
            @Param("state") String state,
            @Param("keyword") String keyword,
            @Param("applicant") String applicant,
            @Param("request") Long request,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until);

    /**
     * 保留经办人曾办理某子项的事实，后续补充重提不覆盖。
     */
    @Insert(
            """
            INSERT INTO access_request_item_action(item_id,actor_id,decision_version)
            VALUES(#{item},#{actor},#{version}) AS current_decision
            ON DUPLICATE KEY UPDATE decision_version=current_decision.decision_version
            """)
    void rememberHandler(@Param("item") long item, @Param("actor") long actor, @Param("version") int version);

    /**
     * 读取当前经办人实际办理版本。仅由服务提取该经办人本人的子项。
     */
    @Select(
            """
            SELECT h.snapshot FROM access_request_revision h
            WHERE h.request_id=#{request} AND EXISTS (
                SELECT 1 FROM access_request_item_action a
                JOIN access_request_item i ON i.id=a.item_id
                WHERE i.request_id=h.request_id AND a.actor_id=#{actor} AND a.decision_version=h.version
            )
            ORDER BY h.version DESC
            """)
    List<String> handledSnapshots(@Param("request") long request, @Param("actor") long actor);

    /**
     * 当前人员实际可回看的历史子项。
     */
    @Select(
            "SELECT a.item_id FROM access_request_item_action a JOIN access_request_item i ON i.id=a.item_id WHERE i.request_id=#{request} AND a.actor_id=#{actor}")
    List<Long> handledItems(@Param("request") long request, @Param("actor") long actor);

    /**
     * 重提后仍保留任何历史办理事实，禁止重新拆分已办单据。
     */
    @Select(
            "SELECT EXISTS (SELECT 1 FROM access_request_item_action a JOIN access_request_item i ON i.id=a.item_id WHERE i.request_id=#{request})")
    boolean hasDecisions(long request);

    /**
     * 责任变更只影响尚在待审批或待分流的子项。
     */
    @Select("SELECT COUNT(*) FROM access_request_item WHERE source_id=#{source} AND state='PENDING'")
    int pendingImpact(long source);

    /**
     * 统计实际引用共享规则且尚未结束的个人来源。
     */
    @Select(
            """
            SELECT COUNT(*) FROM access_grant g
            JOIN access_request_item i ON i.id=g.item_id
            JOIN access_request r ON r.id=i.request_id
            JOIN iam_user u ON u.id=r.applicant_id AND u.lifecycle='ENABLED'
            JOIN ds_source s ON s.id=i.source_id AND s.lifecycle='ENABLED'
            JOIN access_item_rule selection ON selection.item_id=i.id AND selection.rule_id=#{rule}
            WHERE g.ended_at IS NULL AND
            """
                    + JOINT_AVAILABLE)
    int activeRuleGrants(long rule);

    /**
     * 统计有效部门配置引用，不将成员数量和配置数量混用。
     */
    @Select(
            """
            SELECT COUNT(*) FROM access_department_source c
            JOIN iam_department d ON d.id=c.department_id AND d.lifecycle='ENABLED' AND d.access_review_required=0
            JOIN ds_source s ON s.id=c.source_id AND s.lifecycle='ENABLED'
            JOIN access_department_rule selection ON selection.configuration_id=c.id AND selection.rule_id=#{rule}
            WHERE c.ended_at IS NULL
            """)
    int activeRuleDepartments(long rule);

    /**
     * 当前申请规则从受约束的关系获取，按办理时选择顺序返回。
     */
    @Select("SELECT rule_id FROM access_item_rule WHERE item_id=#{id} ORDER BY position")
    List<Long> itemRules(long id);

    /**
     * 联合申请仍需校验上一批准子项的有效规则引用。
     */
    @Insert("INSERT INTO access_item_rule(item_id,rule_id,position) VALUES(#{id},#{rule},#{position})")
    void addItemRule(@Param("id") long id, @Param("rule") long rule, @Param("position") int position);

    /**
     * 重提和重新核定结束旧待办选择，历史正文继续保留在历史修订。
     */
    @Delete("DELETE FROM access_item_rule WHERE item_id=#{id}")
    void clearItemRules(long id);

    /**
     * 配置区间的规则按关系表获取，正文读取当前共享修订。
     */
    @Select("SELECT rule_id FROM access_department_rule WHERE configuration_id=#{id} ORDER BY position")
    List<Long> departmentRules(long id);

    /**
     * 新区间记录本次配置所确定的规则及顺序。
     */
    @Insert("INSERT INTO access_department_rule(configuration_id,rule_id,position) VALUES(#{id},#{rule},#{position})")
    void addDepartmentRule(@Param("id") long id, @Param("rule") long rule, @Param("position") int position);

    /**
     * 来源管理员仅能从授权追溯已实际形成来源的子项。
     */
    @Select(
            "SELECT i.id FROM access_request_item i JOIN access_grant g ON g.item_id=i.id WHERE i.request_id=#{request}")
    List<Long> grantedItems(long request);

    /**
     * 候选人列表仅披露当前可审批人员。
     */
    @Select(
            """
            <script>
            SELECT u.* FROM iam_user u WHERE u.id IN (""" + ELIGIBLE
                    + """
            )
            <if test="pattern != null">AND (LOWER(u.display_name) LIKE #{pattern} ESCAPE '!' OR LOWER(u.username) LIKE #{pattern} ESCAPE '!')</if>
            </script>
            """)
    Page<UserPO> approvers(Page<UserPO> page, @Param("pattern") String pattern);

    /**
     * 当前可办理资格在锁内与列表使用相同条件。
     */
    @Select("SELECT EXISTS (SELECT 1 FROM (" + ELIGIBLE + ") a WHERE a.id=#{id})")
    boolean eligible(long id);

    /**
     * 责任列表范围包括未配置的已启用来源。
     */
    @Select(
            """
            <script>
            SELECT s.* FROM ds_source s
            LEFT JOIN access_responsibility rs ON rs.source_id=s.id
            LEFT JOIN iam_user a ON a.id=rs.primary_user_id
            LEFT JOIN iam_user b ON b.id=rs.alternate_user_id
            WHERE s.lifecycle IN ('ENABLED','DISABLED')
            <if test="sourceName != null">AND LOWER(s.name) LIKE #{sourceName} ESCAPE '!'</if>
            <if test="primary != null">AND (LOWER(a.display_name) LIKE #{primary} ESCAPE '!' OR LOWER(a.username) LIKE #{primary} ESCAPE '!')</if>
            <if test="alternate != null">AND (LOWER(b.display_name) LIKE #{alternate} ESCAPE '!' OR LOWER(b.username) LIKE #{alternate} ESCAPE '!')</if>
            <if test="state == 'UNCONFIGURED'">AND rs.source_id IS NULL</if>
            <if test="state == 'READY' or state == 'REVIEW'">
              AND rs.source_id IS NOT NULL
              AND (rs.primary_user_id IN ("""
                    + ELIGIBLE + """
                   ) AND rs.alternate_user_id IN (""" + ELIGIBLE
                    + """
                   )) = <choose><when test="state == 'READY'">1</when><otherwise>0</otherwise></choose>
            </if>
            </script>
            """)
    Page<DatasourcePO> responsibilities(
            Page<DatasourcePO> page,
            @Param("sourceName") String sourceName,
            @Param("primary") String primary,
            @Param("alternate") String alternate,
            @Param("state") String state);

    /**
     * 仅取正常账号开放的直属归属，恢复待核部和停用源从实时计算排除。
     */
    @Select(
            """
            SELECT cfg.* FROM access_department_source cfg
            JOIN iam_department d ON d.id=cfg.department_id AND d.lifecycle='ENABLED' AND d.access_review_required=0
            JOIN iam_user_department m ON m.department_id=d.id AND m.left_at IS NULL
            JOIN iam_user u ON u.id=m.user_id AND u.lifecycle='ENABLED'
            JOIN ds_source s ON s.id=cfg.source_id AND s.lifecycle='ENABLED'
            WHERE cfg.ended_at IS NULL AND m.user_id=#{user}
            ORDER BY cfg.id
            """)
    List<DepartmentSourcePO> departmentSources(long user);

    /**
     * 个人运行时只读取有效来源，不靠前端传递规则或数据源范围。
     */
    @Select(
            """
            SELECT g.* FROM access_grant g
            JOIN access_request_item i ON i.id=g.item_id
            JOIN access_request r ON r.id=i.request_id
            JOIN iam_user u ON u.id=r.applicant_id AND u.lifecycle='ENABLED'
            JOIN ds_source s ON s.id=i.source_id AND s.lifecycle='ENABLED'
            WHERE g.ended_at IS NULL AND i.state='EFFECTIVE' AND r.applicant_id=#{user}
                AND
            """
                    + JOINT_AVAILABLE + """
            ORDER BY g.id
            """)
    List<AccessGrantPO> personalSources(long user);

    /**
     * 按授权历史事实、实际批准人和当前来源状态组合筛选。
     */
    @Select(
            """
            <script>
            SELECT g.* FROM access_grant g
            JOIN access_request_item i ON i.id=g.item_id
            JOIN access_request r ON r.id=i.request_id
            JOIN iam_user applicant ON applicant.id=r.applicant_id
            JOIN iam_user approver ON approver.id=i.handled_by
            JOIN ds_source s ON s.id=i.source_id
            WHERE 1=1
            <if test="request != null">AND r.id=#{request}</if>
            <if test="source != null">AND s.id=#{source}</if>
            <if test="applicant != null">AND (LOWER(applicant.display_name) LIKE #{applicant} ESCAPE '!' OR LOWER(applicant.username) LIKE #{applicant} ESCAPE '!')</if>
            <if test="handler != null">AND (LOWER(approver.display_name) LIKE #{handler} ESCAPE '!' OR LOWER(approver.username) LIKE #{handler} ESCAPE '!')</if>
            <if test="from != null">AND g.started_at &gt;= #{from}</if>
            <if test="until != null">AND g.started_at &lt; #{until}</if>
            <if test="state == 'ENDED'">AND g.ended_at IS NOT NULL</if>
            <if test="state == 'EFFECTIVE'">AND g.ended_at IS NULL AND applicant.lifecycle='ENABLED' AND s.lifecycle='ENABLED'
                AND
            """
                    + JOINT_AVAILABLE
                    + """
            </if>
            <if test="state == 'SUSPENDED'">AND g.ended_at IS NULL AND (applicant.lifecycle!='ENABLED' OR s.lifecycle!='ENABLED' OR NOT
            """
                    + JOINT_AVAILABLE + """
            )</if>
            </script>
            """)
    Page<AccessGrantPO> grants(
            Page<AccessGrantPO> page,
            @Param("source") Long source,
            @Param("request") Long request,
            @Param("applicant") String applicant,
            @Param("handler") String handler,
            @Param("state") String state,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until);

    /**
     * 过滤原申请授权时，当前本人必须参与同一权威查询。
     */
    @Select(
            """
            <script>
            SELECT g.* FROM access_grant g
            JOIN access_request_item i ON i.id=g.item_id
            JOIN access_request r ON r.id=i.request_id
            WHERE r.applicant_id=#{user}
            <if test="source != null">AND i.source_id=#{source}</if>
            </script>
            """)
    Page<AccessGrantPO> userGrants(Page<AccessGrantPO> page, @Param("user") long user, @Param("source") Long source);

    /**
     * 来源状态和实时权威范围使用相同的组合条件。
     */
    @Select("SELECT " + JOINT_AVAILABLE + " FROM access_request r WHERE r.id=#{request}")
    boolean jointAvailable(long request);

    /**
     * 人员、数据源、来源、经办人和日期均作用在实际可见的两类来源上，再统一排序分页。
     */
    @Select(
            """
            <script>
            SELECT visible.* FROM (
                SELECT 'REQUEST' kind,g.id record_id,0 membership_id,NULL department_id,NULL department_name,
                       u.id user_id,i.source_id,g.started_at,g.ended_at,
                       CASE WHEN g.ended_at IS NOT NULL THEN 'ENDED'
                            WHEN u.lifecycle='ENABLED' AND s.lifecycle='ENABLED' AND
            """
                    + JOINT_AVAILABLE
                    + """
                            THEN 'EFFECTIVE' ELSE 'SUSPENDED' END state,NULL rule_snapshot
                FROM access_grant g
                JOIN access_request_item i ON i.id=g.item_id
                JOIN access_request r ON r.id=i.request_id
                JOIN iam_user u ON u.id=r.applicant_id
                JOIN iam_user h ON h.id=i.handled_by
                JOIN ds_source s ON s.id=i.source_id
                WHERE 1=1
                <if test="source != null">AND i.source_id=#{source}</if>
                <if test="request != null">AND r.id=#{request}</if>
                <if test="applicant != null">AND (LOWER(u.display_name) LIKE #{applicant} ESCAPE '!' OR LOWER(u.username) LIKE #{applicant} ESCAPE '!')</if>
                <if test="approver != null">AND (LOWER(h.display_name) LIKE #{approver} ESCAPE '!' OR LOWER(h.username) LIKE #{approver} ESCAPE '!')</if>
                <if test="origin != null">AND (CAST(r.id AS CHAR) LIKE #{origin} ESCAPE '!' OR '申请授权' LIKE #{origin} ESCAPE '!')</if>
                UNION ALL
                SELECT 'DEPARTMENT' kind,c.id record_id,m.id membership_id,d.id department_id,d.name department_name,
                       u.id user_id,c.source_id,GREATEST(m.joined_at,c.started_at) started_at,
                       COALESCE(LEAST(m.left_at,c.ended_at),m.left_at,c.ended_at) ended_at,
                       CASE WHEN m.left_at IS NOT NULL OR c.ended_at IS NOT NULL THEN 'ENDED'
                            WHEN u.lifecycle!='ENABLED' OR d.lifecycle!='ENABLED' OR d.access_review_required=1 OR s.lifecycle!='ENABLED' THEN 'SUSPENDED'
                            ELSE 'EFFECTIVE' END state,c.rule_snapshot
                FROM access_department_source c
                JOIN iam_department d ON d.id=c.department_id
                JOIN iam_user_department m ON m.department_id=d.id
                JOIN iam_user u ON u.id=m.user_id
                JOIN ds_source s ON s.id=c.source_id
                WHERE (m.left_at IS NULL OR m.left_at &gt; c.started_at)
                  AND (c.ended_at IS NULL OR c.ended_at &gt; m.joined_at)
                <if test="approver != null or request != null">AND 1=0</if>
                <if test="source != null">AND c.source_id=#{source}</if>
                <if test="applicant != null">AND (LOWER(u.display_name) LIKE #{applicant} ESCAPE '!' OR LOWER(u.username) LIKE #{applicant} ESCAPE '!')</if>
                <if test="origin != null">AND (LOWER(d.name) LIKE #{origin} ESCAPE '!' OR LOWER(d.code) LIKE #{origin} ESCAPE '!' OR '部门初始访问' LIKE #{origin} ESCAPE '!')</if>
            ) visible WHERE 1=1
            <if test="state != null">AND visible.state=#{state}</if>
            <if test="from != null">AND visible.started_at &gt;= #{from}</if>
            <if test="until != null">AND visible.started_at &lt; #{until}</if>
            </script>
            """)
    Page<GrantDirectoryRow> grantDirectory(
            Page<GrantDirectoryRow> page,
            @Param("source") Long source,
            @Param("request") Long request,
            @Param("applicant") String applicant,
            @Param("approver") String approver,
            @Param("origin") String origin,
            @Param("state") String state,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until);

    /**
     * 部门配置的历史只与曾在该部门直属任职且实际有重叠区间的记录相交。
     */
    @Select(
            """
            <script>
            SELECT grant_row.* FROM (
                SELECT c.id configuration_id,m.id membership_id,d.id department_id,d.name department_name,
                       u.id user_id,c.source_id,
                       GREATEST(m.joined_at,c.started_at) started_at,
                       COALESCE(LEAST(m.left_at,c.ended_at),m.left_at,c.ended_at) ended_at,
                       CASE WHEN m.left_at IS NOT NULL OR c.ended_at IS NOT NULL THEN 'ENDED'
                            WHEN u.lifecycle!='ENABLED' OR d.lifecycle!='ENABLED'
                                 OR d.access_review_required=1 OR s.lifecycle!='ENABLED' THEN 'SUSPENDED'
                            ELSE 'EFFECTIVE' END state,c.rule_snapshot
                FROM access_department_source c
                JOIN iam_department d ON d.id=c.department_id
                JOIN iam_user_department m ON m.department_id=d.id
                JOIN iam_user u ON u.id=m.user_id
                JOIN ds_source s ON s.id=c.source_id
                WHERE (m.left_at IS NULL OR m.left_at &gt; c.started_at)
                  AND (c.ended_at IS NULL OR c.ended_at &gt; m.joined_at)
                <if test="user != null">AND u.id=#{user}</if>
                <if test="source != null">AND c.source_id=#{source}</if>
                <if test="applicant != null">AND (LOWER(u.display_name) LIKE #{applicant} ESCAPE '!' OR LOWER(u.username) LIKE #{applicant} ESCAPE '!')</if>
                <if test="department != null">AND LOWER(d.name) LIKE #{department} ESCAPE '!'</if>
            ) grant_row WHERE 1=1
            <if test="state != null">AND grant_row.state=#{state}</if>
            <if test="from != null">AND grant_row.started_at &gt;= #{from}</if>
            <if test="until != null">AND grant_row.started_at &lt; #{until}</if>
            </script>
            """)
    Page<DepartmentGrantRow> departmentGrants(
            Page<DepartmentGrantRow> page,
            @Param("user") Long user,
            @Param("source") Long source,
            @Param("applicant") String applicant,
            @Param("department") String department,
            @Param("state") String state,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until);

    /**
     * 本人历史始终与当前认证账号联检。
     */
    @Select(
            """
            SELECT x.version,x.action,x.actor_id,x.created_at FROM access_request_revision x
            JOIN access_request r ON r.id=x.request_id
            WHERE x.request_id=#{request} AND r.applicant_id=#{actor} ORDER BY x.version DESC
            """)
    List<RequestActivityRow> activities(@Param("request") long request, @Param("actor") long actor);

    /**
     * 单据元数据仅披露本人主部门名称。
     */
    @Select(
            """
            SELECT d.name FROM iam_user_department m JOIN iam_department d ON d.id=m.department_id
            WHERE m.user_id=#{user} AND m.is_primary=1 AND m.left_at IS NULL
            """)
    String requestDepartment(long user);

    /**
     * 预览与实际配置使用相同的直属关系口径。
     */
    @Select("SELECT COUNT(*) FROM iam_user_department WHERE department_id=#{department} AND left_at IS NULL")
    long memberCount(long department);

    /**
     * 开放初始配置阻止部门被直接归档。
     */
    @Select(
            "SELECT EXISTS (SELECT 1 FROM access_department_source WHERE department_id=#{department} AND ended_at IS NULL)")
    boolean configured(long department);

    /**
     * 退役核对使用该源的开放部门配置数量。
     */
    @Select("SELECT COUNT(*) FROM access_department_source WHERE source_id=#{source} AND ended_at IS NULL")
    int departmentImpact(long source);

    /**
     * 退役核对使用该源的开放个人来源数量。
     */
    @Select(
            "SELECT COUNT(*) FROM access_grant g JOIN access_request_item i ON i.id=g.item_id WHERE i.source_id=#{source} AND g.ended_at IS NULL")
    int grantImpact(long source);

    /**
     * 归档前保留已结束的配置历史；配置核对完成后开启新的部门修订。
     */
    @Update(
            "UPDATE iam_department SET access_review_required=0,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    void confirmDepartment(long id);

    /**
     * 当前业务时间统一以平台库为准。
     */
    @Select("SELECT UTC_TIMESTAMP(3)")
    LocalDateTime now();

    /**
     * 在当前审批事务内追加参与人及原业务修订。
     */
    @Insert(
            "INSERT INTO access_request_revision(request_id,version,action,actor_id,snapshot) VALUES(#{id},#{version},#{action},#{actor},#{snapshot})")
    void revision(
            @Param("id") long id,
            @Param("version") int version,
            @Param("action") String action,
            @Param("actor") long actor,
            @Param("snapshot") String snapshot);
}
