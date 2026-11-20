package com.dataocean.module.model.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.model.entity.vo.ModelCallVO;
import com.dataocean.module.model.entity.vo.ModelConnectionTestVO;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 模型状态、不可变修订、节点分配和调用记录的数据访问。
 */
public interface ModelMapper {

    /**
     * 配置读取列与记录构造顺序一致，列表查询始终排除凭据。
     */
    String CONFIG_COLUMNS =
            """
            m.id, c.id AS revision_id, c.revision, c.name, c.protocol, c.endpoint, c.model_name,
            c.timeout_seconds, m.enabled, m.version, c.credential_id
            """;

    /**
     * 数据库内配置与独立状态的读模型。
     *
     * @param id 模型 ID
     * @param revisionId 配置修订 ID
     * @param revision 修订号
     * @param name 配置名称
     * @param protocol 接口格式
     * @param endpoint SDK 基址
     * @param modelName 配置中的模型名
     * @param timeoutSeconds 单次上限
     * @param enabled 当前启停
     * @param version 并发版本
     * @param credentialId 秘密修订 ID
     */
    record Config(
            long id,
            long revisionId,
            int revision,
            String name,
            String protocol,
            String endpoint,
            String modelName,
            int timeoutSeconds,
            boolean enabled,
            int version,
            long credentialId) {}

    /**
     * 当前业务节点选择及其并发版本。
     *
     * @param nodeCode 节点编码
     * @param capability 所需模型能力
     * @param modelId 可空模型 ID
     * @param version 节点版本
     */
    record Assignment(String nodeCode, String capability, Long modelId, int version) {}

    /**
     * 调用期间取得的受保护凭据。
     *
     * @param keyVersion 部署密钥版本
     * @param ciphertext 认证加密后的内容
     */
    record Credential(String keyVersion, byte[] ciphertext) {}

    /**
     * 分页读取后台配置列表。
     */
    @Select(
            "SELECT " + CONFIG_COLUMNS
                    + """
             FROM mdl_model m JOIN mdl_config_revision c ON c.id = m.revision_id
             WHERE (#{keyword} IS NULL OR LOCATE(#{keyword}, c.name) > 0)
             ORDER BY m.id DESC
            """)
    Page<Config> list(Page<Config> page, @Param("keyword") String keyword);

    /**
     * 返回节点下拉所需的模型集合，保留已停用选项供回看。
     */
    @Select(
            "SELECT " + CONFIG_COLUMNS
                    + """
             FROM mdl_model m JOIN mdl_config_revision c ON c.id = m.revision_id
             ORDER BY c.name, m.id
            """)
    List<Config> options();

    /**
     * 加锁读取当前配置，保证编辑、停用和分配按同一模型串行校验。
     */
    @Select(
            "SELECT " + CONFIG_COLUMNS
                    + """
             FROM mdl_model m JOIN mdl_config_revision c ON c.id = m.revision_id
             WHERE m.id = #{id} FOR UPDATE
            """)
    Config lockModel(long id);

    /**
     * 读取当前可测试修订，外部请求不持有平台库行锁。
     */
    @Select(
            "SELECT " + CONFIG_COLUMNS
                    + """
             FROM mdl_model m JOIN mdl_config_revision c ON c.id = m.revision_id
             WHERE m.id = #{id}
            """)
    Config current(long id);

    /**
     * 获取任务固定的内容修订及当前模型启停。
     */
    @Select(
            "SELECT " + CONFIG_COLUMNS
                    + """
             FROM mdl_config_revision c JOIN mdl_model m ON m.id = c.model_id
             WHERE c.id = #{id}
            """)
    Config revision(long id);

    /**
     * 建立模型和初始修订指针。
     */
    @Insert("INSERT INTO mdl_model (id, revision_id) VALUES (#{id}, #{revisionId})")
    void insertModel(@Param("id") long id, @Param("revisionId") long revisionId);

    /**
     * 保留一次内容保存的完整、不可变修订。
     */
    @Insert(
            """
            INSERT INTO mdl_config_revision
                (id, model_id, revision, name, protocol, endpoint, model_name, timeout_seconds, credential_id)
            VALUES (#{revisionId}, #{id}, #{revision}, #{name}, #{protocol}, #{endpoint}, #{modelName},
                    #{timeoutSeconds}, #{credentialId})
            """)
    void insertRevision(Config config);

    /**
     * 保存密文并绑定模型资源。
     */
    @Insert(
            """
            INSERT INTO mdl_credential_revision (id, model_id, key_version, ciphertext)
            VALUES (#{id}, #{modelId}, #{keyVersion}, #{ciphertext})
            """)
    void insertCredential(
            @Param("id") long id,
            @Param("modelId") long modelId,
            @Param("keyVersion") String keyVersion,
            @Param("ciphertext") byte[] ciphertext);

    /**
     * 只按已核准的模型与凭据修订取密文。
     */
    @Select(
            """
            SELECT key_version, ciphertext FROM mdl_credential_revision WHERE id = #{id} AND model_id = #{modelId}
            """)
    Credential credential(@Param("id") long id, @Param("modelId") long modelId);

    /**
     * 发布新的配置修订到后续任务。
     */
    @Update("UPDATE mdl_model SET revision_id = #{revisionId}, version = version + 1 WHERE id = #{id}")
    void publish(@Param("id") long id, @Param("revisionId") long revisionId);

    /**
     * 修改当前可执行性，内容修订保持历史事实。
     */
    @Update("UPDATE mdl_model SET enabled = #{enabled}, version = version + 1 WHERE id = #{id}")
    void setEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

    /**
     * 读取已接入的业务节点及选择。
     */
    @Select("SELECT node_code, capability, model_id, version FROM mdl_node_assignment ORDER BY node_code")
    List<Assignment> assignments();

    /**
     * 更新节点分配时锁定确切业务节点。
     */
    @Select(
            "SELECT node_code, capability, model_id, version FROM mdl_node_assignment WHERE node_code = #{node} FOR UPDATE")
    Assignment lockAssignment(String node);

    /**
     * 只修改本次选择的节点。
     */
    @Update("UPDATE mdl_node_assignment SET model_id = #{modelId}, version = version + 1 WHERE node_code = #{node}")
    void assign(@Param("node") String node, @Param("modelId") Long modelId);

    /**
     * 编辑模型能力前检查仍引用它的节点。
     */
    @Select("SELECT COUNT(*) FROM mdl_node_assignment WHERE model_id = #{id}")
    long assignedCount(long id);

    /**
     * 在任务接收时读取已启用模型的当前修订。
     */
    @Select(
            """
            SELECT m.revision_id FROM mdl_node_assignment a JOIN mdl_model m ON m.id = a.model_id
            WHERE a.node_code = #{node} AND m.enabled = TRUE
            """)
    Long binding(String node);

    /**
     * 保存测试诊断，凭据或原始响应不进入记录。
     */
    @Insert(
            """
            INSERT INTO mdl_connection_test (id, revision_id, failure_code)
            VALUES (#{id}, #{revisionId}, #{failureCode})
            """)
    void insertConnectionTest(
            @Param("id") long id, @Param("revisionId") long revisionId, @Param("failureCode") String failureCode);

    /**
     * 只在被查看的配置修订及资源下回读最近发起的测试。
     */
    @Select(
            """
            SELECT t.failure_code IS NULL AS passed, t.failure_code, t.tested_at AS tested_at_utc
            FROM mdl_connection_test t JOIN mdl_config_revision c ON c.id = t.revision_id
            WHERE c.model_id = #{modelId} AND t.revision_id = #{revisionId}
            ORDER BY t.id DESC LIMIT 1
            """)
    ModelConnectionTestVO latestConnectionTest(@Param("modelId") long modelId, @Param("revisionId") long revisionId);

    /**
     * 调用记录通过原任务关联当次修订，旧联调记录保留缺失事实。
     */
    @Select(
            """
            SELECT CAST(g.id AS CHAR) AS id, g.node_code, c.name AS config_name, c.protocol, c.revision,
                   g.model_name, c.endpoint, g.state, g.failure_code, g.elapsed_ms, g.input_tokens,
                   g.output_tokens, g.input_limits, g.recorded_at AS recorded_at_utc
            FROM gov_model_call g JOIN gov_generation_task t ON t.request_id = g.request_id
            LEFT JOIN mdl_config_revision c ON c.id = t.model_revision_id
            ORDER BY g.id DESC
            """)
    Page<ModelCallVO> calls(Page<ModelCallVO> page);
}
