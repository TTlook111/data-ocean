package com.dataocean.module.knowledge.entity.dto;

import com.dataocean.module.governance.entity.dto.GovernanceTargetDTO;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 创建和编辑知识草稿共用的内容。
 */
@Data
public class KnowledgeDocumentDraftDTO {

    /**
     * 业务事项名称。
     */
    @NotBlank
    @Size(max = 120)
    private String title;

    /**
     * 计划支持的业务用途。
     */
    @Size(max = 1000)
    private String purpose;

    /**
     * 已确认的使用边界。
     */
    @Size(max = 1000)
    private String exclusions;

    /**
     * 文档支持的代表性问题。
     */
    @Size(max = 10)
    private List<@NotBlank @Size(max = 240) String> questions = List.of();

    /**
     * 有序正文及实际来源。
     */
    @NotEmpty
    @Size(max = 12)
    private List<@NotNull @Valid Section> sections;

    /**
     * 草稿章节只由用户声明实际主动引用。
     */
    @Data
    public static class Section {

        /**
         * 正文结构的章节名。
         */
        @NotBlank
        @Size(max = 100)
        private String title;

        /**
         * 草稿允许局部留空，确认阶段校验实际正文。
         */
        @Size(max = 6000)
        private String body;

        /**
         * 当前章节主动采用的实际确认。
         */
        @NotNull
        @Size(max = 24)
        private List<@NotNull @Valid Fact> facts = List.of();
    }

    /**
     * 选中的稳定治理身份与用户看到的确切修订。
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Fact extends GovernanceTargetDTO {

        /**
         * 读取选择器时实际获得的确认修订编号。
         */
        @NotBlank
        private String revisionId;
    }
}
