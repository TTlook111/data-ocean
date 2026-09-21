package com.dataocean.module.permission.s1.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.entity.dto.IamS1FieldProtectionSaveDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1FieldProtectionItemVO;
import com.dataocean.module.permission.s1.service.IamS1FieldProtectionService;
import com.dataocean.module.permission.s1.service.IamS1GrantQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * IAM-SIMPLE-1 字段保护。
 * <p>
 * 允许查询不等于允许看原值：字段保护单独维护，不在每个用户授权里复制掩码配置；
 * 保护配置本身不授予字段查询权。
 * </p>
 */
@RestController
@RequestMapping("/api/iam-s1/field-protections")
@RequiredArgsConstructor
public class IamS1FieldProtectionController {

    private final IamS1FieldProtectionService fieldProtectionService;
    private final IamS1GrantQueryService grantQueryService;

    @GetMapping
    public Result<List<IamS1FieldProtectionItemVO>> list(@RequestParam Long datasourceId,
                                                         @RequestParam(required = false) Long snapshotId,
                                                         @RequestParam(required = false) String tableName) {
        return Result.success(grantQueryService.listFieldProtections(UserContext.currentUserId(),
                datasourceId, snapshotId, tableName));
    }

    @PostMapping
    public Result<Long> save(@Valid @RequestBody IamS1FieldProtectionSaveDTO request) {
        return Result.success("字段保护已保存",
                fieldProtectionService.saveProtection(UserContext.currentUserId(), request));
    }

    @DeleteMapping("/{protectionId}")
    public Result<Void> revoke(@PathVariable Long protectionId,
                               @RequestParam(required = false) String reason) {
        fieldProtectionService.revokeProtection(UserContext.currentUserId(), protectionId, reason);
        return Result.success("字段保护已撤销", null);
    }
}
