package com.dataocean.common.util;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * 实体存在性校验工具类
 * <p>
 * 消除各 ServiceImpl 中重复的 requireXxx() 方法，
 * 提供通用的实体存在性校验功能。
 * </p>
 *
 * @author DataOcean
 */
@Slf4j
public class EntityChecker {

    /**
     * 校验实体是否存在，不存在则抛出业务异常
     *
     * @param mapper     MyBatis-Plus Mapper
     * @param id         实体 ID
     * @param entityName 实体名称（用于错误消息）
     * @param <T>        实体类型
     * @param <ID>       ID 类型
     * @return 实体对象
     * @throws BusinessException 实体不存在时抛出
     */
    public static <T, ID extends Serializable> T require(BaseMapper<T> mapper, ID id, String entityName) {
        T entity = mapper.selectById(id);
        if (entity == null) {
            log.warn("实体不存在 entityName={} id={}", entityName, id);
            throw new BusinessException(entityName + "不存在");
        }
        return entity;
    }

    /**
     * 校验实体是否存在（自定义错误码）
     *
     * @param mapper     MyBatis-Plus Mapper
     * @param id         实体 ID
     * @param entityName 实体名称
     * @param errorCode  自定义错误码
     * @param <T>        实体类型
     * @param <ID>       ID 类型
     * @return 实体对象
     * @throws BusinessException 实体不存在时抛出
     */
    public static <T, ID extends Serializable> T require(BaseMapper<T> mapper, ID id, String entityName, int errorCode) {
        T entity = mapper.selectById(id);
        if (entity == null) {
            log.warn("实体不存在 entityName={} id={} errorCode={}", entityName, id, errorCode);
            throw new BusinessException(errorCode, entityName + "不存在");
        }
        return entity;
    }
}
