package com.dataocean.module.iam.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.query.CommandLookupQuery;
import com.dataocean.module.iam.entity.vo.CommandStatusVO;
import com.dataocean.module.iam.service.MutationService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 回包不明时只向原命令主体提供当前可披露的提交事实。
 */
@RestController
@RequiredArgsConstructor
public class CommandController {

    /**
     * 重新核验当前动作资格的命令查询服务。
     */
    private final MutationService mutations;

    /**
     * commandId 与当前用户及原动作共同定位唯一命令。
     */
    @GetMapping("/api/v1/commands/{commandId}")
    public Result<CommandStatusVO> status(
            @PathVariable String commandId, @Valid @ModelAttribute CommandLookupQuery query) {
        return Result.success(mutations.status(commandId, query.getOperationCode()));
    }
}
