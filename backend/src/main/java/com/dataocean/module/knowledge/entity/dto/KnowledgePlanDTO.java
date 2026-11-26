package com.dataocean.module.knowledge.entity.dto;

import com.dataocean.module.knowledge.entity.dto.KnowledgeDocumentDraftDTO.Fact;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 发起当前源的文档规划，目标用于表达业务需求。
 *
 * @param goal 可选的业务目标或代表性问题
 * @param facts 主动选中的同源治理确认，省略时读取本源有效确认
 */
public record KnowledgePlanDTO(@Size(max = 300) String goal, @Size(max = 200) List<@NotNull @Valid Fact> facts) {}
