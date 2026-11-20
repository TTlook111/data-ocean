package com.dataocean.module.knowledge.entity.vo;

import com.dataocean.module.governance.entity.vo.GovernanceTargetVO;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 回读所选文档、确切修订及实际依赖。
 */
@Data
@AllArgsConstructor
public class KnowledgeDocumentVO {

    /**
     * 稳定文档与当前状态。
     */
    private Row document;

    /**
     * 当前请求读取的确切正文标识。
     */
    private String revisionId;

    /**
     * 查阅历史时保留当时内容。
     */
    private Content content;

    /**
     * 本修订中各章节的实际依赖。
     */
    private List<Reference> references;

    /**
     * 只追加的修订清单。
     */
    private List<Revision> history;

    /**
     * 文档列表和详情复用聚合状态。
     */
    @Data
    @AllArgsConstructor
    public static class Row {

        /**
         * 文档稳定编号。
         */
        private String id;

        /**
         * 文档的展示名称。
         */
        private String title;

        /**
         * 可写命令必须使用实际聚合版本。
         */
        private int version;

        /**
         * 当前草稿的确切修订。
         */
        private String headRevisionId;

        /**
         * 已获人工确认的修订，可与草稿不同。
         */
        private String confirmedRevisionId;

        /**
         * 当前草稿、已确认或依据待复核。
         */
        private String state;

        /**
         * 对现行确认负责的人员和时间。
         */
        private String confirmedBy;

        /**
         * 确认实际发生的时间。
         */
        private Instant confirmedAt;
    }

    /**
     * 文档内容的类型化存储结构。
     */
    @Data
    @AllArgsConstructor
    public static class Content {

        /**
         * 修订标题和用途。
         */
        private String title;

        /**
         * 业务使用目的。
         */
        private String purpose;

        /**
         * 已确认的使用边界。
         */
        private String exclusions;

        /**
         * 代表性问题。
         */
        private List<String> questions;

        /**
         * 保留业务叙述的章节次序。
         */
        private List<Section> sections;
    }

    /**
     * 一段可编辑正文，来源由固定引用单独承载。
     */
    @Data
    @AllArgsConstructor
    public static class Section {

        /**
         * 章节名称。
         */
        private String title;

        /**
         * 已保存正文。
         */
        private String body;
    }

    /**
     * 固定章节引用的可回读信息。
     */
    @Data
    @AllArgsConstructor
    public static class Reference {

        /**
         * 章节的原保存次序，从零开始。
         */
        private int sectionIndex;

        /**
         * 源内稳定的治理对象和条目。
         */
        private GovernanceTargetVO target;

        /**
         * 实际引用的确认修订。
         */
        private String factRevisionId;

        /**
         * 固定的对象世代与结构版本。
         */
        private int generation;

        /**
         * 表或列的实际结构修订。
         */
        private String structureRevisionId;

        /**
         * 是否为用户主动确认的范围。
         */
        private boolean selected;

        /**
         * 录入本次修订时保存的展示名称。
         */
        private String label;
    }

    /**
     * 修订历史的摘要及可直达的固定编号。
     */
    @Data
    @AllArgsConstructor
    public static class Revision {

        /**
         * 固定的正文修订编号。
         */
        private String id;

        /**
         * 文档内递增的修订号。
         */
        private int number;

        /**
         * 实际操作者。
         */
        private String createdBy;

        /**
         * 修订写入时间。
         */
        private Instant createdAt;
    }
}
