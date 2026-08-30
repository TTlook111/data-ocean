package com.dataocean.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.system.entity.SysOperationLog;
import com.dataocean.module.system.entity.dto.OperationLogQueryDTO;
import com.dataocean.module.system.mapper.SysOperationLogMapper;
import com.dataocean.module.system.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
     * 分页查询操作日志，支持多条件动态过滤
     *
     * @param query 查询条件（操作人/类型/状态/时间范围/IP/路径/目标资源/目标ID/关键词）
     */
    @Override
    public Page<SysOperationLog> listLogs(OperationLogQueryDTO query) {
        if (query == null) {
            query = new OperationLogQueryDTO();
        }
        int page = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 20 : query.getPageSize();

        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<SysOperationLog>()
                .orderByDesc(SysOperationLog::getCreatedAt);

        wrapper.like(StringUtils.hasText(query.getOperatorName()), SysOperationLog::getOperatorName, trim(query.getOperatorName()));
        wrapper.eq(StringUtils.hasText(query.getOperationType()), SysOperationLog::getOperationType, trim(query.getOperationType()));
        wrapper.eq(query.getIsSuccess() != null, SysOperationLog::getIsSuccess, query.getIsSuccess());
        wrapper.like(StringUtils.hasText(query.getIpAddress()), SysOperationLog::getIpAddress, trim(query.getIpAddress()));
        wrapper.like(StringUtils.hasText(query.getRequestPath()), SysOperationLog::getRequestPath, trim(query.getRequestPath()));
        wrapper.eq(StringUtils.hasText(query.getTargetResource()), SysOperationLog::getTargetResource, trim(query.getTargetResource()));
        wrapper.eq(StringUtils.hasText(query.getTargetId()), SysOperationLog::getTargetId, trim(query.getTargetId()));

        // 时间范围：start/end 单个存在时用 ge/le；两者都有且 start <= end 时用 between；start > end 视为无效范围忽略
        LocalDateTime start = parseTime(query.getStartTime());
        LocalDateTime end = parseTime(query.getEndTime());
        if (start != null && end != null && !start.isAfter(end)) {
            wrapper.between(SysOperationLog::getCreatedAt, start, end);
        } else if (start != null && end == null) {
            wrapper.ge(SysOperationLog::getCreatedAt, start);
        } else if (end != null && start == null) {
            wrapper.le(SysOperationLog::getCreatedAt, end);
        }

        // 关键词跨字段模糊搜索（超长截断，防止超长输入拖慢 LIKE 查询）
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = trim(query.getKeyword());
            if (keyword.length() > 100) {
                keyword = keyword.substring(0, 100);
            }
            String normalizedKeyword = keyword;
            wrapper.and(w -> w.like(SysOperationLog::getOperatorName, normalizedKeyword)
                    .or().like(SysOperationLog::getTargetResource, normalizedKeyword)
                    .or().like(SysOperationLog::getRequestPath, normalizedKeyword));
        }

        return operationLogMapper.selectPage(new Page<>(page, pageSize), wrapper);
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : value;
    }

    /**
     * 解析时间参数，支持 "yyyy-MM-dd" 或 "yyyy-MM-dd HH:mm:ss"，解析失败返回 null 并降级忽略该条件
     */
    private LocalDateTime parseTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String v = value.trim();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        try {
            if (v.length() <= 10) {
                return LocalDateTime.parse(v + " 00:00:00", formatter);
            }
            return LocalDateTime.parse(v, formatter);
        } catch (Exception e) {
            log.warn("操作日志时间参数解析失败，已忽略该条件: {}", value);
            return null;
        }
    }
}
