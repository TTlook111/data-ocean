package com.dataocean.module.permission.s1.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.entity.IamS1SnapshotOption;
import com.dataocean.module.permission.s1.entity.vo.IamS1ColumnOptionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1TableOptionVO;
import com.dataocean.module.permission.s1.service.IamS1ResourceOptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * IAM-SIMPLE-1 授权与字段保护表单的资源选项。
 * <p>
 * 只读取已发布元数据快照的表字段资源事实，不读取旧数据授权或业务原始记录；
 * 每次读取都按“负责源”强制校验。
 * </p>
 */
@RestController
@RequestMapping("/api/iam-s1/datasources")
@RequiredArgsConstructor
public class IamS1ResourceOptionController {

    private final IamS1ResourceOptionService resourceOptionService;

    @GetMapping("/{datasourceId}/snapshots")
    public Result<List<IamS1SnapshotOption>> snapshots(@PathVariable Long datasourceId) {
        return Result.success(resourceOptionService.publishedSnapshots(UserContext.currentUserId(), datasourceId));
    }

    @GetMapping("/{datasourceId}/snapshots/{snapshotId}/tables")
    public Result<List<IamS1TableOptionVO>> tables(@PathVariable Long datasourceId,
                                                    @PathVariable Long snapshotId) {
        return Result.success(resourceOptionService.tables(UserContext.currentUserId(), datasourceId, snapshotId));
    }

    @GetMapping("/{datasourceId}/snapshots/{snapshotId}/tables/{tableName}/columns")
    public Result<List<IamS1ColumnOptionVO>> columns(@PathVariable Long datasourceId,
                                                     @PathVariable Long snapshotId,
                                                     @PathVariable String tableName) {
        return Result.success(resourceOptionService.columns(UserContext.currentUserId(), datasourceId,
                snapshotId, tableName));
    }
}
