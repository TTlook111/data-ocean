package com.dataocean.module.model.controller;

import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.model.entity.dto.ModelAssignmentDTO;
import com.dataocean.module.model.entity.dto.ModelSaveDTO;
import com.dataocean.module.model.entity.dto.ModelStateDTO;
import com.dataocean.module.model.entity.vo.ModelCallVO;
import com.dataocean.module.model.entity.vo.ModelConfigVO;
import com.dataocean.module.model.entity.vo.ModelConnectionTestVO;
import com.dataocean.module.model.entity.vo.ModelNodeVO;
import com.dataocean.module.model.service.ModelRegistry;
import com.dataocean.module.model.service.impl.ModelConnectionTestService;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台模型配置、逐节点选择和调用记录入口。
 */
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    /**
     * 版本化模型配置和诊断服务。
     */
    private final ModelRegistry registry;

    /**
     * 独立的合成连接测试及回读。
     */
    private final ModelConnectionTestService connectionTests;

    /**
     * 读取可披露的当前模型配置。
     */
    @GetMapping
    @RequirePermission("models.view")
    public Result<PageVO<ModelConfigVO>> list(PageQuery query, @RequestParam(required = false) String keyword) {
        return Result.success(registry.list(query, keyword));
    }

    /**
     * 新增可供相应节点分配的生成模型。
     */
    @PostMapping
    @RequirePermission("models.create")
    public Result<ModelConfigVO> create(@Valid @RequestBody ModelSaveDTO body) {
        return Result.success(registry.save(null, body));
    }

    /**
     * 保存新修订，凭据替换在服务内另外检查资格。
     */
    @PutMapping("/{id}")
    @RequirePermission("models.edit")
    public Result<ModelConfigVO> edit(@PathVariable long id, @Valid @RequestBody ModelSaveDTO body) {
        return Result.success(registry.save(id, body));
    }

    /**
     * 停用或恢复现有模型。
     */
    @PutMapping("/{id}/state")
    @RequirePermission("models.disable")
    public Result<Boolean> state(@PathVariable long id, @Valid @RequestBody ModelStateDTO body) {
        registry.setState(id, body);
        return Result.success(true);
    }

    /**
     * 测试当前保存的模型配置。
     */
    @PostMapping("/{id}/connection-tests/{revisionId}")
    @RequirePermission("models.test")
    public Result<ModelConnectionTestVO> testConnection(@PathVariable long id, @PathVariable long revisionId) {
        return Result.success(connectionTests.test(id, revisionId));
    }

    /**
     * 按详情的修订展示已知的最新测试结果。
     */
    @GetMapping("/{id}/connection-tests/{revisionId}")
    @RequirePermission("models.view")
    public Result<ModelConnectionTestVO> latestConnectionTest(@PathVariable long id, @PathVariable long revisionId) {
        return Result.success(connectionTests.latest(id, revisionId));
    }

    /**
     * 获取已经接通的业务节点及各自的选择。
     */
    @GetMapping("/nodes")
    @RequirePermission("models.view")
    public Result<List<ModelNodeVO>> nodes() {
        return Result.success(registry.nodes());
    }

    /**
     * 获取节点选择需要的完整模型选项。
     */
    @GetMapping("/options")
    @RequirePermission("models.view")
    public Result<List<ModelConfigVO>> options() {
        return Result.success(registry.options());
    }

    /**
     * 分配只作用于选定节点。
     */
    @PutMapping("/nodes/{node}")
    @RequirePermission("models.assign")
    public Result<Boolean> assign(@PathVariable String node, @Valid @RequestBody ModelAssignmentDTO body) {
        registry.assign(node, body);
        return Result.success(true);
    }

    /**
     * 查看最初调用时绑定的配置修订及可披露诊断元数据。
     */
    @GetMapping("/calls")
    @RequirePermission("diagnostics.view")
    public Result<PageVO<ModelCallVO>> calls(PageQuery query) {
        return Result.success(registry.calls(query));
    }
}
