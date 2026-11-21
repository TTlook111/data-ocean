package com.dataocean.module.knowledge.service;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentDependencyMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.mapper.KnowledgeWithdrawalMapper;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 必要文档关系独立于引用共同的表、字段和指标，正式调用使用固定的依赖组合。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentLinks {

    /**
     * 文档同源核对不以标题或事实相同猜测依赖。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 当前生效入口及冻结的知识修订。
     */
    private final KnowledgePublicationMapper bindings;

    /**
     * 读取和固定显式引用。
     */
    private final KnowledgeDocumentDependencyMapper links;

    /**
     * 已撤下的目标无法参与新的正式组合。
     */
    private final KnowledgeWithdrawalMapper withdrawals;

    /**
     * 将草稿声明转为同源、去重且有稳定排序的直接引用。
     */
    public List<Long> capture(long source, Long owner, List<String> selected) {
        var ids = selected.stream().map(Ids::parse).distinct().sorted().toList();
        for (long id : ids) {
            // 历史和草稿都可保留引用，但不能自引或越源。
            if (Objects.equals(owner, id) || documents.get(source, id) == null) {
                throw BizIllegalException.invalid("文档引用须为同一数据源的其他文档");
            }
        }
        return ids;
    }

    /**
     * 回读正文修订实际保存的直接关系。
     */
    public List<Long> documents(long revision) {
        return links.requiredDocuments(revision);
    }

    /**
     * 只向本次追加的文档修订保存关系。
     */
    public void save(long revision, List<Long> dependencies) {
        dependencies.forEach(id -> links.add(revision, id));
    }

    /**
     * 在发布核对和来源锁内各解析一次确切生效依赖。
     */
    public List<Long> prepare(long source, long document, long revision) {
        var required = documents(revision).stream()
                .map(id -> {
                    // 草稿可以声明未发布的目标，但正式入口必须是已经核实的有效绑定。
                    var target = bindings.activeForDocument(source, id);
                    if (target == null) {
                        throw stale();
                    }
                    return target.id();
                })
                .toList();
        walk(source, document, required, ignored -> {});
        return required;
    }

    /**
     * 同一事务记录已经作为发布依据核对过的直接绑定。
     */
    public void pin(long binding, List<Long> required) {
        required.forEach(id -> links.pin(binding, id));
    }

    /**
     * 逐个检查确切版本和适用于当前用途的安全条件。
     */
    public void verifyPinned(Binding owner, Consumer<Binding> check) {
        walk(owner.sourceId(), owner.documentId(), links.requiredBindings(owner.id()), check);
    }

    /**
     * 迭代核对固定依赖，重叠路径只验证一次。
     */
    private void walk(long source, long owner, List<Long> required, Consumer<Binding> check) {
        var queue = new ArrayDeque<>(required);
        var visited = new HashSet<Long>();
        while (!queue.isEmpty()) {
            long id = queue.removeFirst();
            if (!visited.add(id)) {
                // 多个分支引用同一个绑定时只处理一次。
                continue;
            }
            var target = bindings.get(source, id);
            if (target == null
                    || !Objects.equals(target.activeBindingId(), id)
                    || target.verifiedAt() == null
                    || withdrawals.withdrawn(id)) {
                throw stale();
            }
            if (target.documentId() == owner) {
                // 拒绝形成指回本次文档的循环依据。
                throw BizIllegalException.invalid("文档引用形成循环，请重新选择必要引用");
            }
            check.accept(target);
            queue.addAll(links.requiredBindings(id));
        }
    }

    /**
     * 旧组合不能借另一个已发布版本自动取得使用资格。
     */
    private BizIllegalException stale() {
        return new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "必要引用的正式版本已变化、未核实或已撤下，请重新核对");
    }
}
