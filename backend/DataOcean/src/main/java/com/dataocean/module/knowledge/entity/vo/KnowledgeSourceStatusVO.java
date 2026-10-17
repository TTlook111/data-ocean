package com.dataocean.module.knowledge.entity.vo;
import java.util.List;
public record KnowledgeSourceStatusVO(boolean valid, Long snapshotId, List<String> reasons) {}
