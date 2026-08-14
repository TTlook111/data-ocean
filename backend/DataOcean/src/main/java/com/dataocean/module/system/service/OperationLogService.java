package com.dataocean.module.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.system.entity.SysOperationLog;
import com.dataocean.module.system.entity.dto.OperationLogQueryDTO;

public interface OperationLogService {
    void record(SysOperationLog log);
    Page<SysOperationLog> listLogs(OperationLogQueryDTO query);
}
