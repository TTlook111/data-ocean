package com.dataocean.module.iam.mapper;

import com.dataocean.module.iam.entity.po.GuardPO;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 公共契约的持久化适配，业务状态和权限由所属服务核验。
 */
public interface GuardMapper {

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
     * 读取当前权威状态或可信登录主体，缺失时不回退默认身份。
     */
    @Select("SELECT * FROM iam_security_guard WHERE id=1")
    GuardPO current();

    /**
     * 在已持有配置锁的事务中递增全局安全版本。
     */
    @Update("UPDATE iam_security_guard SET security_version=security_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=1")
    int increment();

    /**
     * 首次必要改密成功后永久关闭空库初始化例外。
     */
    @Update("UPDATE iam_security_guard SET bootstrap_completed=1,updated_at=UTC_TIMESTAMP(3) WHERE id=1")
    int completeBootstrap();

    /**
     * 在同一数据库连接中取得本次插入的稳定标识。
     */
    @Select("SELECT LAST_INSERT_ID()")
    long lastId();
}
