package com.dataocean.module.query.service;

import com.dataocean.module.query.entity.dto.IamS1AttemptAuthorizeRequestDTO;
import com.dataocean.module.query.entity.dto.IamS1AttemptResultRequestDTO;

import java.util.Map;

/** Java-owned per-attempt authorization, one-shot execution handoff and final result protection. */
public interface IamS1QueryAttemptService {
    Map<String, Object> authorize(String taskId, IamS1AttemptAuthorizeRequestDTO request);

    Map<String, Object> markExecuting(String taskId, String attemptId, String sqlHash);

    Map<String, Object> protectResult(String taskId, IamS1AttemptResultRequestDTO request);
}
