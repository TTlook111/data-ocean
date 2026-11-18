package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.iam.entity.po.GuardPO;

import org.apache.ibatis.annotations.Select;

/**
 * 公共契约的持久化适配，业务状态和权限由所属服务核验。
 */
public interface GuardMapper extends BaseMapper<GuardPO> {

    /**
     * 领取当前配置或业务记录的排他锁，提交前重新核验权威事实。
     */
    @Select("SELECT * FROM iam_security_guard WHERE id=1 FOR UPDATE")
    GuardPO lock();

    /**
     * 读取与授权配置写互斥的共享快照，避免在变更中途混合旧新事实。
     */
    @Select("SELECT * FROM iam_security_guard WHERE id=1 FOR SHARE")
    GuardPO shared();

    /**
     * 在已持有配置锁的事务中递增全局安全版本。
     */
    default int increment() {
        return update(Wrappers.<GuardPO>lambdaUpdate()
                .eq(GuardPO::getId, 1)
                .setIncrBy(GuardPO::getSecurityVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 首次必要改密成功后永久关闭空库初始化例外。
     */
    default int completeBootstrap() {
        return update(Wrappers.<GuardPO>lambdaUpdate()
                .eq(GuardPO::getId, 1)
                .set(GuardPO::isBootstrapCompleted, true)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 在同一数据库连接中取得本次插入的稳定标识。
     */
    @Select("SELECT LAST_INSERT_ID()")
    long lastId();
}
