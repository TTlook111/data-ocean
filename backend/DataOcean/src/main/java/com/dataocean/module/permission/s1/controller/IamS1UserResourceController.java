package com.dataocean.module.permission.s1.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.entity.IamS1SnapshotOption;
import com.dataocean.module.permission.s1.entity.vo.IamS1ColumnOptionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1TableOptionVO;
import com.dataocean.module.permission.s1.service.IamS1UserResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * IAM-SIMPLE-1 用户侧资源选择（问数资源声明、访问申请）。
 * <p>
 * 与后台配置资源接口（`/api/iam-s1/datasources/**`，要求 `security:permission:view` + 负责源）分离：
 * 这里的可见性由“主体命中且当前有效的 ALLOW 数据授权”决定（QUERY），
 * 或由“存在已发布快照的启用数据源”决定（APPLY），普通问数用户不需要后台负责源。
 * </p>
 * <p>
 * 两个 scope 都要求“使用问数”功能；返回内容不含连接信息、密码或业务记录。
 * </p>
 */
@RestController
@RequestMapping("/api/iam-s1/query-resources")
@RequiredArgsConstructor
public class IamS1UserResourceController {

    private final IamS1UserResourceService userResourceService;

    /** 可选数据源：scope=QUERY 为有数据授权的源，scope=APPLY 为可申请（存在已发布快照）的源。 */
    @GetMapping("/datasources")
    public Result<List<IamS1DatasourceRefVO>> datasources(@RequestParam(required = false) String scope) {
        return Result.success(userResourceService.datasources(UserContext.currentUserId(), scope));
    }

    @GetMapping("/datasources/{datasourceId}/snapshots")
    public Result<List<IamS1SnapshotOption>> snapshots(@PathVariable Long datasourceId,
                                                       @RequestParam(required = false) String scope) {
        return Result.success(userResourceService.publishedSnapshots(
                UserContext.currentUserId(), scope, datasourceId));
    }

    @GetMapping("/datasources/{datasourceId}/snapshots/{snapshotId}/tables")
    public Result<List<IamS1TableOptionVO>> tables(@PathVariable Long datasourceId,
                                                   @PathVariable Long snapshotId,
                                                   @RequestParam(required = false) String scope) {
        return Result.success(userResourceService.tables(
                UserContext.currentUserId(), scope, datasourceId, snapshotId));
    }

    @GetMapping("/datasources/{datasourceId}/snapshots/{snapshotId}/tables/{tableName}/columns")
    public Result<List<IamS1ColumnOptionVO>> columns(@PathVariable Long datasourceId,
                                                     @PathVariable Long snapshotId,
                                                     @PathVariable String tableName,
                                                     @RequestParam(required = false) String scope) {
        return Result.success(userResourceService.columns(
                UserContext.currentUserId(), scope, datasourceId, snapshotId, tableName));
    }
}
