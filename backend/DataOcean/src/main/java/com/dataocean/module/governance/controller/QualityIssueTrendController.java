package com.dataocean.module.governance.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.governance.entity.vo.QualityIssueTrendVO;
import com.dataocean.module.governance.service.QualityIssueTrendService;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/snapshots")
@RequiredArgsConstructor
public class QualityIssueTrendController {
    private static final String ISSUE_VIEW = "governance:issue:view";
    private final IamS1CapabilityService capabilities;
    private final QualityIssueTrendService trends;

    /** 沿用当前问题的同源读权限；单纯查看趋势不会启动检查或修改问题。 */
    @GetMapping("/{snapshotId}/quality-issues/trend")
    @IamS1Resource(function = ISSUE_VIEW, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#snapshotId")
    public Result<QualityIssueTrendVO> trend(@PathVariable long snapshotId, @RequestParam(defaultValue = "7") int days) {
        var sources = capabilities.responsibleDatasourcesWithFunction(UserContext.currentUserId(), ISSUE_VIEW)
                .stream().map(IamS1DatasourceRefVO::id).toList();
        return Result.success(trends.read(snapshotId, days, sources));
    }
}
