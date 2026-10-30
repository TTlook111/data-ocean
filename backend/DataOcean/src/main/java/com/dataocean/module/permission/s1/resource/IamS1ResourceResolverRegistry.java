package com.dataocean.module.permission.s1.resource;

import com.dataocean.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资源解析器固定注册表。
 *
 * <p>Controller 不允许自行拼接解析逻辑，只能通过本注册表按资源类型取解析器。</p>
 *
 * <p>启动时校验“一种资源类型有且只有一个解析器”；取用未注册类型一律 fail-closed。</p>
 */
@Component
public class IamS1ResourceResolverRegistry {

    private final Map<IamS1ResourceType, IamS1ResourceResolver> resolvers;

    public IamS1ResourceResolverRegistry(List<IamS1ResourceResolver> resolverList) {
        Map<IamS1ResourceType, IamS1ResourceResolver> registered = new EnumMap<>(IamS1ResourceType.class);
        for (IamS1ResourceResolver resolver : resolverList) {
            IamS1ResourceType type = resolver.supports();
            if (type == null) {
                throw new IllegalStateException("S1 资源解析器未声明支持的类型: " + resolver.getClass().getName());
            }
            if (registered.put(type, resolver) != null) {
                throw new IllegalStateException("S1 资源类型存在重复解析器: " + type);
            }
        }
        this.resolvers = Map.copyOf(registered);
    }

    /** 取解析器；未注册类型直接拒绝。 */
    public IamS1ResourceResolver require(IamS1ResourceType type) {
        IamS1ResourceResolver resolver = type == null ? null : resolvers.get(type);
        if (resolver == null) {
            throw new BusinessException(500, "未注册的 S1 资源类型：" + type);
        }
        return resolver;
    }

    /** 已注册的资源类型，供覆盖扫描测试核对。 */
    public Set<IamS1ResourceType> registeredTypes() {
        return resolvers.keySet();
    }
}
