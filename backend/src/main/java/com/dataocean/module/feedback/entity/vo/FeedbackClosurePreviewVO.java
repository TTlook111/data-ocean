package com.dataocean.module.feedback.entity.vo;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 结案人确认的原提问人及受控回告。
 */
@Data
@AllArgsConstructor
public class FeedbackClosurePreviewVO {

    /**
     * 核对预览时主事项的当前版本。
     */
    private int version;

    /**
     * 原问题接收人的编号。
     */
    private String recipientId;

    /**
     * 原问题接收人的显示名称。
     */
    private String recipientName;

    /**
     * 当前接收资格所适用的固定模板编码。
     */
    private String template;

    /**
     * 当前模板产生的站内回告正文。
     */
    private String message;

    /**
     * 汇集多个原问题时需要一并核对的各个接收人及其回告。
     */
    private List<Recipient> recipients;

    /**
     * 多原问预览用于核对接收人、模板和正文的确认值。
     */
    private String confirmation;

    /**
     * 单个接收人的当前受控回告，字段顺序沿用既有多原问确认值的生成口径。
     */
    @Data
    @AllArgsConstructor
    @JsonPropertyOrder({"recipientId", "recipientName", "template", "message"})
    public static class Recipient {

        /**
         * 实际接收该回告的原提问人编号。
         */
        private String recipientId;

        /**
         * 实际接收人的显示名称。
         */
        private String recipientName;

        /**
         * 根据本人当前资格选定的模板编码。
         */
        private String template;

        /**
         * 该模板此时会显示给接收人的正文。
         */
        private String message;
    }
}
