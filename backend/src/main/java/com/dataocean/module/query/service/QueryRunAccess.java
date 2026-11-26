package com.dataocean.module.query.service;

import static com.dataocean.module.query.service.QueryProtocol.TERMINAL;

import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.iam.service.ServiceActorPermission;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 执行与澄清共用原发起人及在途权限核对。
 */
@Service
@RequiredArgsConstructor
class QueryRunAccess {

    /**
     * 查询当前轮次的租约、取消与终态。
     */
    private final QueryRequestMapper requests;

    /**
     * 核验并安装可信发起者。
     */
    private final ServiceActorPermission actors;

    /**
     * 解析发起者当前来源权限。
     */
    private final AccessScopeService access;

    /**
     * 核对已冻结的实际连接配置。
     */
    private final GovernanceCatalog catalog;

    /**
     * 确认当前发布仍可用于正式问数。
     */
    private final KnowledgeVersionService versions;

    /**
     * 计算与查询输出证据一致的规则签名。
     */
    private final QueryOutput output = new QueryOutput();

    /**
     * 当前请求取消、过期以及权限变化都先于下一外发节点。
     */
    AccessScopeVO checked(Request row) {
        var latest = requests.get(row.id());
        if (latest.runVersion() != row.runVersion()
                || latest.leaseUntil() == null
                || !latest.leaseUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            // 旧代次或已到期的租约不能继续执行。
            throw QueryFailure.technical();
        }
        if (latest.cancelRequested() || TERMINAL.contains(latest.state())) {
            // 取消或已经结束的事实优先于下一次外发。
            throw new QueryFailure("USER_CANCELLED");
        }
        return actorScope(row);
    }

    /**
     * 实时核对发起人有效权限和实际数据源授权。
     */
    AccessScopeVO actorScope(Request row) {
        actors.require(row.userId(), row.authenticationVersion(), "query.view", "query.ask");
        return access.requireSource(row.userId(), row.sourceId());
    }

    /**
     * 当前规则的变更必须重新请求执行，本轮旧模型检查不复用。
     */
    List<OutputRuleVO> requireRules(Request row, String signature) {
        var rules = checked(row).getRules();
        if (!signature.equals(output.signature(rules))) {
            // 继续执行必须保持输出权限的原受控范围。
            throw QueryFailure.denied();
        }
        return rules;
    }

    /**
     * 冻结的绑定、权限和连接配置在执行及交付前原子复核，并提供已核对的当前规则。
     */
    List<OutputRuleVO> verifyBinding(Request row, long binding, String signature, Long configuration) {
        var rules = requireRules(row, signature);
        asActor(row, () -> {
            // 连接修订和发布资格均以同一可信发起人检查。
            if (configuration != null
                    && !configuration.equals(
                            catalog.source(row.sourceId(), true).configurationId())) {
                // 已经冻结的执行只能使用原来的实际连接修订。
                throw QueryFailure.technical();
            }
            return versions.requirePinnedForQuery(row.sourceId(), binding);
        });
        return rules;
    }

    /**
     * 将可信发起者临时安装给必须复用会话检查的下游。
     */
    <T> T asActor(Request row, Supplier<T> read) {
        return actors.asActor(row.userId(), row.authenticationVersion(), read, "query.view", "query.ask");
    }
}
