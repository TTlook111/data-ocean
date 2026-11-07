package com.dataocean.module.dashboard.entity.vo;

import java.time.LocalDateTime;

/** 慢回答只返回标识与耗时；点踩只返回原反馈域有权展示的原因，不附带问题、SQL 或结果。 */
public record QueryExperienceDetailVO(String taskId, Long datasourceId, LocalDateTime createdAt,
                                      Long seconds, String reasonCode, String comment) {}
