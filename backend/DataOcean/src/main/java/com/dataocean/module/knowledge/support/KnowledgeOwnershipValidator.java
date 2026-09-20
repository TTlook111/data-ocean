package com.dataocean.module.knowledge.support;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.entity.VectorIndexTask;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 知识文档归属链的统一校验点。
 *
 * <p>文档的归属是 `knowledge_doc.datasource_id`；它的**每一个版本**还各自带 `datasource_id`
 * 与可选的 `metadata_snapshot_id`，而快照又有自己的真实数据源。只校验文档本身不足以保护
 * 「按 docId 读取历史版本」的接口：</p>
 *
 * <ul>
 *   <li>版本列表 / 版本详情 / 版本差异：会返回历史版本内容与来源快照；</li>
 *   <li>审核记录 / 来源快照：由版本派生；</li>
 *   <li>回滚：会把某个历史版本的内容**重新写入**并创建向量化任务；</li>
 *   <li>索引任务：任务自带 `datasource_id`，与文档不一致时说明事实已经错位。</li>
 * </ul>
 *
 * <p>因此所有版本、快照与向量任务的读取与写入都共用本校验器；任一归属不完整或跨源一律 409，
 * 不挑一个用、也不忽略不合法的那一条继续处理。</p>
 *
 * <p>只读 S1/业务只读事实，不读取旧角色权限、旧数据授权或旧缓存。</p>
 */
@Component
@RequiredArgsConstructor
public class KnowledgeOwnershipValidator {

    private final KnowledgeDocVersionMapper versionMapper;
    private final MetadataSnapshotMapper snapshotMapper;

    /**
     * 校验文档当前版本的归属。
     *
     * <p>`currentVersion` 为空或 ≤ 0 表示“尚无版本”（历史数据或刚建好的文档），这是**显式允许**
     * 的状态而不是“随便缺失都放行”；只要它指向一个版本号，该版本记录就必须存在且归属一致。</p>
     */
    public void requireCurrentVersionOwnership(KnowledgeDoc doc) {
        Integer currentVersion = doc.getCurrentVersion();
        if (currentVersion == null || currentVersion <= 0) {
            return;
        }
        KnowledgeDocVersion version = versionMapper.selectOwnershipByDocAndVersionNo(doc.getId(), currentVersion);
        if (version == null) {
            throw new BusinessException(409,
                    "文档的当前版本记录不存在（currentVersion=" + currentVersion + "），无法判定负责范围");
        }
        requireVersionOwnership(doc, version);
    }

    /**
     * 校验单个版本的归属：`datasource_id` 必须存在且等于文档的数据源；
     * 版本上的来源快照若存在，必须存在且属于同一数据源。
     *
     * <p>`metadataSnapshotId` 为空是**合法**状态：手工创建的文档没有来源快照。</p>
     */
    public void requireVersionOwnership(KnowledgeDoc doc, KnowledgeDocVersion version) {
        if (doc == null || version == null) {
            return;
        }
        Long docDatasourceId = doc.getDatasourceId();
        if (docDatasourceId == null) {
            throw new BusinessException(409, "知识文档缺少数据源归属，无法判定负责范围");
        }
        Long versionDatasourceId = version.getDatasourceId();
        if (versionDatasourceId == null) {
            throw new BusinessException(409, "知识文档版本缺少数据源归属，无法判定负责范围");
        }
        if (!versionDatasourceId.equals(docDatasourceId)) {
            throw new BusinessException(409, "知识文档版本属于其它数据源，拒绝返回");
        }
        if (version.getMetadataSnapshotId() != null) {
            requireSnapshotOwnership(docDatasourceId, version.getMetadataSnapshotId());
        }
    }

    /** 批量校验版本的归属；任一条不合法即整体拒绝。 */
    public void requireVersionsOwnership(KnowledgeDoc doc, Collection<KnowledgeDocVersion> versions) {
        if (versions == null) {
            return;
        }
        for (KnowledgeDocVersion version : versions) {
            requireVersionOwnership(doc, version);
        }
    }

    /**
     * 校验来源快照的归属：快照必须存在、归属完整且与给定数据源一致。
     *
     * <p>写入路径（生成草稿 / 回滚创建新版本）也调用它：与其让一条跨源版本落库、
     * 之后再在读取时 409，不如在写入前就拒绝。</p>
     */
    public void requireSnapshotOwnership(Long datasourceId, Long snapshotId) {
        if (snapshotId == null) {
            return;
        }
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new BusinessException(409, "来源快照不存在，无法判定负责范围");
        }
        if (snapshot.getDatasourceId() == null) {
            throw new BusinessException(409, "来源快照缺少数据源归属，无法判定负责范围");
        }
        if (datasourceId == null || !snapshot.getDatasourceId().equals(datasourceId)) {
            throw new BusinessException(409, "来源快照属于其它数据源，拒绝使用");
        }
    }

    /**
     * 校验向量化任务的归属：任务的 `datasource_id` 必须与文档一致。
     *
     * <p>任务与文档不一致说明事实已经错位，继续返回会把另一个数据源的索引状态
     * 展示成本文档的状态。</p>
     */
    public void requireVectorTaskOwnership(KnowledgeDoc doc, Collection<VectorIndexTask> tasks) {
        if (doc == null || tasks == null) {
            return;
        }
        Long docDatasourceId = doc.getDatasourceId();
        for (VectorIndexTask task : tasks) {
            Long taskDatasourceId = task.getDatasourceId();
            if (taskDatasourceId == null || !taskDatasourceId.equals(docDatasourceId)) {
                throw new BusinessException(409, "向量化任务的数据源与文档不一致，拒绝返回");
            }
        }
    }
}
