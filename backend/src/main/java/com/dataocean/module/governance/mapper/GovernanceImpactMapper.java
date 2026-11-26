package com.dataocean.module.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.governance.entity.po.GovernanceImpactPO;
import com.dataocean.module.governance.service.GovernanceKnowledgeProgress.Target;

import java.util.List;

import org.apache.ibatis.annotations.Select;

/**
 * 每个来源变化对应条目的实际办理事实。
 */
public interface GovernanceImpactMapper extends BaseMapper<GovernanceImpactPO> {

    /**
     * 来源复核完成后仍在等待知识处理的实际目标。
     */
    @Select(
            """
            SELECT p.id,g.object_id,g.kind,g.target_id,p.baseline_revision_id
            FROM gov_impact_task p JOIN gov_item g ON g.id=p.item_id
            WHERE p.source_id=#{source} AND g.source_id=#{source} AND p.state='REVIEWED'
              AND p.knowledge_required=TRUE AND p.knowledge_completed_at IS NULL
            ORDER BY p.id
            """)
    List<Target> pendingKnowledge(long source);
}
