package com.dataocean.module.knowledge.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 展示构建实际阶段、具体失败及已核验结果。
 */
@Data
@AllArgsConstructor
public class KnowledgeReleaseBuildVO {

    /**
     * 与任务及隔离索引一一对应的构建代次。
     */
    private String id;

    /**
     * 当前状态，排队、构建中、成功或失败。
     */
    private String state;

    /**
     * 已转换成维护人员可见的稳定失败代码。
     */
    private String failureCode;

    /**
     * Strong 回读核验的片段数，只有实际成功后填写。
     */
    private Integer verifiedChunks;

    /**
     * 本代次实际向量维数，只有实际成功后填写。
     */
    private Integer vectorDimension;

    /**
     * 接收本次构建命令的 UTC 时间。
     */
    private Instant createdAt;
}
