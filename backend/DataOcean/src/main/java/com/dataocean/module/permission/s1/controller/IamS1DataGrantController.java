package com.dataocean.module.permission.s1.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataGrantSaveDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataGrantVO;
import com.dataocean.module.permission.s1.service.IamS1DataGrantService;
import com.dataocean.module.permission.s1.service.IamS1GrantQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * IAM-SIMPLE-1 数据授权配置。
 * <p>
 * 一次说明“谁能查哪些数据”：主体、数据源、表和字段、记录范围和有效期都必须明确；
 * 字段为空不表示全部字段，记录条件只接受结构化字段与操作符，不接受手写 SQL。
 * 服务端强制的校验在 {@code IamS1DataGrantService} 内完成。
 * </p>
 */
@RestController
@RequestMapping("/api/iam-s1/data-grants")
@RequiredArgsConstructor
public class IamS1DataGrantController {

    private final IamS1DataGrantService dataGrantService;
    private final IamS1GrantQueryService grantQueryService;

    @GetMapping
    public Result<List<IamS1DataGrantVO>> list(@RequestParam Long datasourceId,
                                                @RequestParam(required = false) String subjectType,
                                                @RequestParam(required = false) Long subjectId,
                                                @RequestParam(required = false) String tableName,
                                                @RequestParam(required = false) String status) {
        return Result.success(grantQueryService.listGrants(UserContext.currentUserId(), datasourceId,
                subjectType, subjectId, tableName, status));
    }

    @GetMapping("/{grantId}")
    public Result<IamS1DataGrantVO> get(@PathVariable Long grantId) {
        return Result.success(grantQueryService.getGrant(UserContext.currentUserId(), grantId));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody IamS1DataGrantSaveDTO request) {
        return Result.success("数据授权已保存",
                dataGrantService.createGrant(UserContext.currentUserId(), request));
    }

    @PostMapping("/batch")
    public Result<Void> createBatch(@Valid @RequestBody List<IamS1DataGrantSaveDTO> requests,
                                    @RequestParam(required = false) String reason) {
        dataGrantService.createGrants(UserContext.currentUserId(), requests, reason);
        return Result.success("数据授权已批量保存", null);
    }

    @PutMapping("/{grantId}")
    public Result<Void> update(@PathVariable Long grantId, @Valid @RequestBody IamS1DataGrantSaveDTO request) {
        dataGrantService.updateGrant(UserContext.currentUserId(), grantId, request);
        return Result.success("数据授权已更新", null);
    }

    @DeleteMapping("/{grantId}")
    public Result<Void> revoke(@PathVariable Long grantId, @RequestParam(required = false) String reason) {
        dataGrantService.revokeGrant(UserContext.currentUserId(), grantId, reason);
        return Result.success("数据授权已撤销", null);
    }
}
