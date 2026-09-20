package com.dataocean.module.permission.s1.resource.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.support.KnowledgeOwnershipValidator;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * KNOWLEDGE_DOCUMENT：语义知识文档的真实归属。
 *
 * <p>归属链：`knowledge_doc.datasource_id`，再由
 * {@link KnowledgeOwnershipValidator} 复核文档**当前版本**的归属（版本 `datasource_id` 必须存在且
 * 与文档一致；版本上的来源快照若存在必须存在且属于同一数据源）。</p>
 *
 * <p>为什么当前版本必须复核：文档的版本可以带来源快照，而快照自己也有真实数据源。
 * 只信文档的 `datasource_id` 时，一条把快照写错（或历史数据写坏）的版本会让调用者
 * 以“负责文档所在源”的身份读到另一个数据源的元数据内容。不一致一律 409，不挑一个用。</p>
 *
 * <p>只校验当前版本是不够的——按 docId 读取历史版本的接口（版本列表/详情/差异/审核记录/
 * 来源快照/回滚/索引任务）还会读到更早的版本，因此它们由
 * {@code KnowledgeVersionServiceImpl} 对**每一个**版本调用同一个校验器。</p>
 *
 * <p>只读 S1/业务只读事实，不读取旧角色权限、旧数据授权或旧缓存。</p>
 */
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentResourceResolver implements IamS1ResourceResolver {

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeOwnershipValidator ownershipValidator;

    @Override
    public IamS1ResourceType supports() {
        return IamS1ResourceType.KNOWLEDGE_DOCUMENT;
    }

    @Override
    public IamS1ResolvedResource resolve(Object resourceId) {
        Long id = IamS1ResourceIds.asLong(resourceId);
        if (id == null) {
            throw new BusinessException(400, "缺少知识文档参数，无法判定负责范围");
        }
        KnowledgeDoc doc = docMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(404, "知识文档不存在");
        }
        if (doc.getDatasourceId() == null) {
            // 归属断链：不能当成“无归属即可放行”。
            throw new BusinessException(409, "知识文档缺少数据源归属，无法判定负责范围");
        }
        // currentVersion 为空或 ≤ 0 表示“尚无版本”，显式允许；
        // 只要它指向一个版本号，该版本记录就必须存在且归属一致，否则 409。
        ownershipValidator.requireCurrentVersionOwnership(doc);
        return new IamS1ResolvedResource(IamS1ResourceType.KNOWLEDGE_DOCUMENT, id,
                doc.getDatasourceId(), null, null, null);
    }
}
