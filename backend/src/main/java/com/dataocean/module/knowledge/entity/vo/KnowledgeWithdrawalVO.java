package com.dataocean.module.knowledge.entity.vo;

import java.time.Instant;
import java.util.List;

/**
 * 可核对的完整撤下范围及办理历史。
 */
public final class KnowledgeWithdrawalVO {

    /**
     * 类型分组通过明示的接口记录返回。
     */
    private KnowledgeWithdrawalVO() {}

    /**
     * @param bindingId 本次退出的实际正式绑定
     * @param title 冻结的正式名称
     * @param revision 冻结的文档修订号
     * @param depth 从本次目标到引用方的最短距离，零为目标
     */
    public record Member(String bindingId, String title, int revision, int depth) {}

    /**
     * @param members 本次需完整退出的实际绑定
     * @param reviewToken 仅对本次清单有效的核对凭据
     */
    public record Preview(List<Member> members, String reviewToken) {}

    /**
     * @param id 原撤下批次
     * @param reason 提交时的实际原因
     * @param actor 撤下办理人
     * @param at 实际提交时间
     * @param members 原批次退出成员
     */
    public record Detail(String id, String reason, String actor, Instant at, List<Member> members) {}
}
