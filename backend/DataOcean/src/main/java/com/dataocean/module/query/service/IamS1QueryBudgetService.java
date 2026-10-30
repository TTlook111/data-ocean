package com.dataocean.module.query.service;

import com.dataocean.module.query.entity.dto.IamS1ModelCallBudgetDTO;
import java.util.Map;

/** Durable G0 LLM count, per-call output bound, and cost ceiling. */
public interface IamS1QueryBudgetService {
    Map<String, Object> reserve(String taskId, IamS1ModelCallBudgetDTO request);
    Map<String, Object> settle(String taskId, IamS1ModelCallBudgetDTO request);
}
