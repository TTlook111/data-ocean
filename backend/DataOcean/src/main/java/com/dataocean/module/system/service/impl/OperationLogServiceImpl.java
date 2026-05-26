package com.dataocean.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.system.entity.SysOperationLog;
import com.dataocean.module.system.mapper.SysOperationLogMapper;
import com.dataocean.module.system.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 操作日志服务实现类
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperationLogServiceImpl implements OperationLogService {

    private final SysOperationLogMapper operationLogMapper;

    /**
     * 异步记录操作日志
     *
     * @param opLog 操作日志实体
     */
    @Override
    @Async
    public void record(SysOperationLog opLog) {
        try {
            operationLogMapper.insert(opLog);
        } catch (Exception e) {
            log.error("操作日志写入失败", e);
        }
    }

    /**
     * 分页查询操作日志
     *
     * @param page           页码
     * @param pageSize       每页大小
     * @param targetResource 目标资源过滤（可选）
     */
    @Override
    public Page<SysOperationLog> listLogs(int page, int pageSize, String targetResource) {
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<SysOperationLog>()
                .orderByDesc(SysOperationLog::getCreatedAt);
        if (StringUtils.hasText(targetResource)) {
            wrapper.eq(SysOperationLog::getTargetResource, targetResource);
        }
        return operationLogMapper.selectPage(new Page<>(page, pageSize), wrapper);
    }
}
