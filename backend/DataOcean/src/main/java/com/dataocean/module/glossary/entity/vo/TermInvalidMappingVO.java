package com.dataocean.module.glossary.entity.vo;

/**
 * 快照发布后已失效、但保留历史记录的术语字段映射。
 *
 * <p>失效映射不参与当前审核摘要，也不进入知识事实；页面必须明确提示需要重新确认，
 * 不能把它当成“从未关联”或“仍然可用”。</p>
 */
public record TermInvalidMappingVO(Long entityId, String previousFqn, String reason,
                                   Long invalidatedSnapshotId) {
}
