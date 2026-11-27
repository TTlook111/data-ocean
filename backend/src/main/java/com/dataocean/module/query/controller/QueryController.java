package com.dataocean.module.query.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.query.entity.dto.AskQueryDTO;
import com.dataocean.module.query.entity.dto.ClarifyQueryDTO;
import com.dataocean.module.query.entity.vo.QueryAcceptedVO;
import com.dataocean.module.query.entity.vo.QueryConversationVO;
import com.dataocean.module.query.entity.vo.QueryExplanationVO;
import com.dataocean.module.query.entity.vo.QuerySnapshotVO;
import com.dataocean.module.query.entity.vo.QueryTurnVO;
import com.dataocean.module.query.entity.vo.QueryWorkbenchSourceVO;
import com.dataocean.module.query.service.QueryExplanations;
import com.dataocean.module.query.service.QueryService;
import com.dataocean.module.query.service.QueryStream;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

/**
 * 从同一会话接收单源问数并回读其真实状态。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/query")
public class QueryController {

    /** 当前用户命令与结果边界。 */
    private final QueryService queries;
    /** 解释的实际修订与重试权限。 */
    private final QueryExplanations explanations;
    /** 只订阅当前状态。 */
    private final QueryStream stream;

    /**
     * 返回本人可回看历史的来源及其当下的问数提交资格。
     */
    @GetMapping("/sources")
    @RequirePermission("query.view")
    public Result<List<QueryWorkbenchSourceVO>> sources() {
        return Result.success(queries.sources());
    }

    /** 列出当前源的本人查询轮次。 */
    @GetMapping("/requests")
    @RequirePermission("query.view")
    public Result<List<QueryTurnVO>> history(@RequestParam String sourceId) {
        return Result.success(queries.history(Ids.parse(sourceId)));
    }

    /** 按当前源定位本人会话，避免跨源上下文。 */
    @GetMapping("/conversations")
    @RequirePermission("query.view")
    public Result<List<QueryConversationVO>> conversations(@RequestParam String sourceId) {
        return Result.success(queries.conversations(Ids.parse(sourceId)));
    }

    /** 实际会话的近期轮次可逐条回看。 */
    @GetMapping("/conversations/{id}/requests")
    @RequirePermission("query.view")
    public Result<List<QueryTurnVO>> turns(@PathVariable String id) {
        return Result.success(queries.turns(id));
    }

    /** 接收后立即回原轮次号，独立工作者处理真实执行。 */
    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission("query.ask")
    public Result<QueryAcceptedVO> ask(
            @RequestBody AskQueryDTO input, @RequestHeader("Idempotency-Key") String command) {
        return Result.success(queries.ask(input, command));
    }

    /** 回读状态及可披露结果和依据。 */
    @GetMapping("/requests/{id}")
    @RequirePermission("query.view")
    public Result<QuerySnapshotVO> read(@PathVariable String id) {
        return Result.success(queries.read(id));
    }

    /** 补足业务条件时继续同一个轮次。 */
    @PostMapping("/requests/{id}/clarifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission("query.ask")
    public Result<QueryAcceptedVO> clarify(
            @PathVariable String id,
            @RequestBody ClarifyQueryDTO input,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(queries.answer(id, command, input.getAnswer()));
    }

    /** 登记取消而不猜测源端停止状态。 */
    @PostMapping("/requests/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission("query.ask")
    public Result<QueryAcceptedVO> cancel(@PathVariable String id) {
        return Result.success(queries.cancel(id));
    }

    /** 在原结果上登记新的解释修订，网络重试返回同一回执。 */
    @PostMapping("/requests/{id}/explanations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission("query.ask")
    public Result<QueryExplanationVO> retryExplanation(
            @PathVariable String id, @RequestHeader("Idempotency-Key") String command) {
        return Result.success(explanations.retry(id, command));
    }

    /** SSE 只读取当前轮次，交付权限实时核验。 */
    @GetMapping(value = "/requests/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermission("query.view")
    public SseEmitter events(@PathVariable String id, HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            throw BizIllegalException.denied();
        }
        return stream.open(id, session.getId());
    }
}
