package com.dataocean.module.glossary.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dataocean.module.glossary.entity.GlossaryTerm;

import java.util.List;

/**
 * 术语条目服务接口
 *
 * @author dataocean
 */
public interface GlossaryTermService extends IService<GlossaryTerm> {

    /**
     * 创建术语（自动设置 FQN 和初始状态）
     */
    GlossaryTerm createTerm(GlossaryTerm term);

    /**
     * 提交术语审核
     */
    void submitForReview(Long termId);

    /**
     * 审核术语（通过或拒绝）
     */
    void reviewTerm(Long termId, Long reviewerId, boolean approved, String reason);

    /**
     * 修改术语内容。
     * <p>
     * 只允许修改 DRAFT 或 REJECTED 状态的术语——已通过或待审核的术语必须先退回草稿，
     * 使「修改已通过术语必须重走审核」这一约束成立。只接受内容字段的白名单赋值，
     * 请求体无法借由本方法篡改状态或审核记录。
     * </p>
     *
     * @param termId 术语 ID
     * @param patch  待修改的内容字段
     * @return 更新后的术语
     */
    GlossaryTerm updateTerm(Long termId, GlossaryTerm patch);

    /**
     * 把已通过的术语退回草稿（APPROVED → DRAFT）。
     * <p>
     * 状态机原先从 APPROVED 没有出边，已通过的术语无法合法修改。本方法补上该路径，
     * 并清空审核记录，使修改后的内容必须重新走审核。
     * </p>
     *
     * @param termId 术语 ID
     */
    void revertToDraft(Long termId);

    /**
     * 删除术语，并清理其关联数据。
     *
     * @param termId 术语 ID
     */
    void deleteTerm(Long termId);

    /**
     * 级联删除某术语表下的全部术语，并清理关联数据。
     *
     * @param glossaryId 术语表 ID
     * @return 删除的术语数量
     */
    int deleteTermsOfGlossary(Long glossaryId);
}
