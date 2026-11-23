package com.dataocean.module.knowledge.service;

import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeVersionPauseDTO;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;

/**
 * 正式知识的使用资格、当前绑定与版本限制。
 */
public interface KnowledgeVersionService {

    /**
     * 暂停整个当前单文档范围，保留原版本和索引。
     */
    MutationVO pause(long source, long id, String command, KnowledgeVersionPauseDTO input);

    /**
     * 正式问数使用绑定前及在途重新校验时通过同一权威入口。
     * 反馈回看会捕获旧绑定等业务拒绝并裁剪结果，拒绝仍向调用方抛出，不将外层事务标记为回滚。
     */
    Binding requireForQuery(long sourceId, long id);

    /**
     * 以反馈记录中的复验人复核查询资格，结案人的会话仅用于结案授权。
     */
    void requireForFeedbackReplay(long verifier, long sourceId, long id);

    /**
     * 办理和续作检查路径来源及同一实际在用绑定。
     */
    Binding current(long source, long id);

    /**
     * 恢复验证可以针对手动暂停的范围，其他治理禁止和结构或模型限制依然生效。
     */
    void requireBasis(Source source, Binding binding);

    /**
     * 暂停随发布范围生效，即使同一范围切换正式版本也保持限制。
     */
    boolean isPaused(long scope);

    /**
     * 已撤下只影响历史中确切的绑定。
     */
    boolean isWithdrawn(long binding);

    /**
     * 使用控制的表单和写操作同时核验页面、正文及源访问。
     */
    void requireOperator(long source);
}
