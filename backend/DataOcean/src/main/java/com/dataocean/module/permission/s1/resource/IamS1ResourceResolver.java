package com.dataocean.module.permission.s1.resource;

/**
 * 资源归属解析器。
 *
 * <p>实现只能依赖最小只读 Mapper/查询服务，禁止反向依赖 Controller，
 * 也禁止调用带副作用的业务 Service（解析发生在授权之前，此时还没有获得授权）。</p>
 *
 * <p>解析器必须重新查询真实归属，**不能**采信请求自带的 datasourceId。</p>
 */
public interface IamS1ResourceResolver {

    /** 本解析器支持的资源类型。 */
    IamS1ResourceType supports();

    /**
     * 解析资源归属。
     *
     * @param resourceId 资源 ID；null 表示调用方没提供，交由实现决定是否拒绝
     * @return 解析结果；资源不存在或归属断链时返回 {@code datasourceId == null} 的结果，
     * 或直接抛出业务异常——两种方式调用方都按拒绝处理
     */
    IamS1ResolvedResource resolve(Object resourceId);
}
