package com.dataocean.module.metadata.service;

/** 字段治理“掩码候选”的确认与拒绝编排。 */
public interface MetadataMaskCandidateService {

    /**
     * 确认掩码候选：写入 S1 字段保护事实并清除候选标记，**同一事务**。
     *
     * <p>两件事必须原子：只写保护不清标记，接口报成功但候选仍挂着，再次确认会重复写入；
     * 只清标记不写保护，则用户以为脱敏已生效而实际没有。由于 V55 的
     * `iam_s1_field_protection` 没有唯一约束，本方法还负责幂等——同列同策略重复确认不会累积多条 ACTIVE。</p>
     */
    void confirm(Long operatorUserId, Long entityId, String maskStrategy);

    /** 拒绝掩码候选：只清除候选标记，不产生任何权限事实。 */
    void reject(Long operatorUserId, Long entityId);
}
