package com.dataocean.module.permission.s1.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.pagination.PageRequest;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.entity.dto.IamS1AccessRequestSubmitDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1AccessReviewDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1AccessRequestVO;
import com.dataocean.module.permission.s1.service.IamS1AccessRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * IAM-SIMPLE-1 新体系访问申请与审批。
 * <p>
 * 我的申请与管理员审批保持独立入口：业务用户不进入后台权限工作区，
 * 管理员只看负责数据源的队列；通过后生成独立的新体系授权事实。
 * </p>
 */
@RestController
@RequestMapping("/api/iam-s1/access-requests")
@RequiredArgsConstructor
public class IamS1AccessRequestController {

    private final IamS1AccessRequestService accessRequestService;

    @PostMapping
    public Result<Long> submit(@Valid @RequestBody IamS1AccessRequestSubmitDTO request) {
        return Result.success("申请已提交，等待审批",
                accessRequestService.submit(UserContext.currentUserId(), request));
    }

    @GetMapping("/mine")
    public Result<List<IamS1AccessRequestVO>> mine() {
        return Result.success(accessRequestService.listMine(UserContext.currentUserId()));
    }

    /**
     * 审批队列：按状态分组真分页，避免待审批记录被同源的已处理记录挤出。
     *
     * @param status `PENDING`（待审批）/ `HANDLED`（已处理）/ 留空（全部）
     */
    @GetMapping("/queue")
    public Result<Page<IamS1AccessRequestVO>> queue(@RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "1") Integer page,
                                                    @RequestParam(defaultValue = "20") Integer size) {
        return Result.success(accessRequestService.listQueue(UserContext.currentUserId(), status,
                (int) PageRequest.page(page), (int) PageRequest.size(size)));
    }

    @PostMapping("/{requestId}/withdraw")
    public Result<Void> withdraw(@PathVariable Long requestId,
                                 @RequestParam(required = false) String reason) {
        accessRequestService.withdraw(UserContext.currentUserId(), requestId, reason);
        return Result.success("申请已撤回", null);
    }

    @PostMapping("/{requestId}/review")
    public Result<Map<String, Object>> review(@PathVariable Long requestId,
                                              @Valid @RequestBody IamS1AccessReviewDTO request) {
        Long grantId = accessRequestService.review(UserContext.currentUserId(), requestId, request);
        return Result.success("审批已完成", Map.of(
                "requestId", requestId,
                "generatedGrantId", grantId == null ? "" : grantId));
    }
}
