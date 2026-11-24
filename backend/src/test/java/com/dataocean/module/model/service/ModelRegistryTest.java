package com.dataocean.module.model.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.CredentialProtection;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.model.entity.dto.ModelAssignmentDTO;
import com.dataocean.module.model.mapper.ModelMapper;
import com.dataocean.module.model.mapper.ModelMapper.Assignment;
import com.dataocean.module.model.mapper.ModelMapper.Config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证直接保存模型分配也遵循实际业务调用入口。
 */
class ModelRegistryTest {

    /**
     * 模拟平台读取现有分配并写入确认结果。
     */
    private final ModelMapper models = mock(ModelMapper.class);

    /**
     * 仅经正式分配入口验证，凭据不参与测试。
     */
    private final ModelRegistry registry =
            new ModelRegistry(models, mock(CredentialProtection.class), mock(PermissionService.class));

    /**
     * 共用模型及尚未接入的节点都不能保存新的模型分配。
     */
    @ParameterizedTest
    @ValueSource(strings = {"clarification", "document-write"})
    void refusesIneffectiveAssignment(String node) {
        var choice = availableChoice(node);

        assertThatThrownBy(() -> registry.assign(node, choice))
                .isInstanceOf(BizIllegalException.class)
                .hasMessageContaining("当前节点没有独立模型调用");
        verify(models, never()).assign(node, choice.getModelId());
    }

    /**
     * 已接通节点继续保存能力匹配且版本一致的选择。
     */
    @ParameterizedTest
    @ValueSource(strings = {"intent", "sql-generate", "document-plan"})
    void savesConnectedAssignment(String node) {
        var choice = availableChoice(node);

        registry.assign(node, choice);

        verify(models).assign(node, choice.getModelId());
    }

    /**
     * 构造版本一致且能力匹配的选择，单独验证调用入口的限制。
     */
    private ModelAssignmentDTO availableChoice(String node) {
        var choice = new ModelAssignmentDTO();
        choice.setModelId(7L);
        choice.setVersion(3);
        when(models.lockModel(7L))
                .thenReturn(new Config(
                        7,
                        8,
                        1,
                        "验收模型",
                        "OPENAI_CHAT_COMPLETIONS",
                        "https://models.example.invalid",
                        "sample",
                        20,
                        true,
                        1,
                        9));
        when(models.lockAssignment(node)).thenReturn(new Assignment(node, "GENERATION", null, 3));
        return choice;
    }
}
