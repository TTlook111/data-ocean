package com.dataocean.module.model.mapper;

import com.dataocean.module.model.entity.dto.ModelCallFinishDTO;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 在平台库按单次提供方请求登记与更新回执。
 */
public interface ModelCallReceiptMapper {

    /**
     * 节点及修订能力相符才登记，回执重传不改写首次时间。
     */
    @Insert(
            """
            INSERT INTO mdl_provider_call(id,module_code,origin_id,node_code,model_revision_id,review_at)
            SELECT #{id},#{module},#{originId},n.node_code,c.id,UTC_TIMESTAMP(3) + INTERVAL 180 SECOND
            FROM mdl_node_assignment n JOIN mdl_config_revision c ON c.id=#{revision}
            WHERE n.node_code=#{node} AND n.capability=CASE c.protocol
                WHEN 'OPENAI_EMBEDDINGS' THEN 'EMBEDDING' WHEN 'COHERE_RERANK' THEN 'RERANK' ELSE 'GENERATION' END
            ON DUPLICATE KEY UPDATE id=mdl_provider_call.id
            """)
    void start(String id, String module, String originId, String node, long revision);

    /**
     * 校验同一标识重传时仍属于原业务节点和快照。
     */
    @Select("SELECT module_code,origin_id,node_code,model_revision_id FROM mdl_provider_call WHERE id=#{id}")
    Identity identity(String id);

    /**
     * 只接收首个及时结果。截止后自动归待核查，保留已对账的历史边界。
     */
    @Update(
            """
            UPDATE mdl_provider_call SET completed_at=UTC_TIMESTAMP(3),model_name=#{result.modelName},
              failure_code=#{result.failureCode},elapsed_ms=#{result.elapsedMs},
              input_tokens=#{result.inputTokens},output_tokens=#{result.outputTokens}
            WHERE id=#{id} AND completed_at IS NULL AND review_at > UTC_TIMESTAMP(3)
            """)
    int finish(String id, ModelCallFinishDTO result);

    /**
     * 登记后固定的业务身份及配置修订。
     *
     * @param moduleCode 来源业务模块
     * @param originId 发出模型请求的业务任务
     * @param nodeCode 实际执行节点
     * @param modelRevisionId 当次使用的不可变配置修订
     */
    record Identity(String moduleCode, String originId, String nodeCode, long modelRevisionId) {}
}
