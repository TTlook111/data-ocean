package com.dataocean.common.persistence;

import com.dataocean.common.exception.BusinessException;

/**
 * MyBatis-Plus 乐观锁更新结果校验。
 * <p>
 * {@code updateById} 在版本条件不匹配时返回 0，而不是抛出业务异常。
 * 所有带 {@code @Version} 实体的写路径都必须显式调用本工具，避免冲突被误报为成功。
 * </p>
 */
public final class OptimisticLockSupport {

    private OptimisticLockSupport() {
    }

    /**
     * 校验更新是否命中带版本条件的记录。
     *
     * @param affectedRows 更新影响行数
     * @param message      返回给调用方的中文冲突提示
     */
    public static void requireUpdated(int affectedRows, String message) {
        if (affectedRows == 0) {
            throw new BusinessException(409, message);
        }
    }
}
