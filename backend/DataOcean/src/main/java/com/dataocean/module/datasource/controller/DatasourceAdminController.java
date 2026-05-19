package com.dataocean.module.datasource.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.entity.query.DatasourceQuery;
import com.dataocean.module.datasource.entity.dto.DatasourceAccessGrantDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceCreateDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceStatusUpdateDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceTestDTO;
import com.dataocean.module.datasource.entity.dto.DatasourceUpdateDTO;
import com.dataocean.module.datasource.entity.vo.DatasourceAccessVO;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestVO;
import com.dataocean.module.datasource.entity.vo.DatasourceVO;
import com.dataocean.module.datasource.service.DatasourceAccessService;
import com.dataocean.module.datasource.service.DatasourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/datasources")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('datasource:manage')")
@Slf4j
public class DatasourceAdminController {

    private final DatasourceService datasourceService;
    private final DatasourceAccessService accessService;

    @GetMapping
    public Result<Page<DatasourceVO>> listDatasources(@ModelAttribute DatasourceQuery request) {
        return Result.success(datasourceService.listDatasources(request));
    }

    @GetMapping("/{id}")
    public Result<DatasourceVO> getDatasource(@PathVariable Long id) {
        return Result.success(datasourceService.getDatasourceById(id));
    }

    @PostMapping
    public Result<DatasourceVO> createDatasource(@Valid @RequestBody DatasourceCreateDTO request) {
        log.debug("收到创建数据源请求 name={} host={} database={}", request.getName(), request.getHost(), request.getDatabaseName());
        return Result.success("创建成功", datasourceService.createDatasource(request));
    }

    @PutMapping("/{id}")
    public Result<DatasourceVO> updateDatasource(@PathVariable Long id,
                                                 @Valid @RequestBody DatasourceUpdateDTO request) {
        log.debug("收到更新数据源请求 datasourceId={} name={}", id, request.getName());
        return Result.success("更新成功", datasourceService.updateDatasource(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteDatasource(@PathVariable Long id) {
        datasourceService.deleteDatasource(id);
        return Result.success("删除成功", null);
    }

    @PatchMapping("/{id}/status")
    public Result<DatasourceVO> updateStatus(@PathVariable Long id,
                                             @Valid @RequestBody DatasourceStatusUpdateDTO request) {
        return Result.success("状态更新成功", datasourceService.updateStatus(id, request.getStatus()));
    }

    @PostMapping("/test-connection")
    public Result<DatasourceConnectionTestVO> testConnection(@Valid @RequestBody DatasourceTestDTO request) {
        log.debug("收到数据源连接测试请求 host={} database={}", request.getHost(), request.getDatabaseName());
        return Result.success(datasourceService.testConnection(request));
    }

    @PostMapping("/{id}/test-connection")
    public Result<DatasourceConnectionTestVO> testSavedConnection(@PathVariable Long id) {
        return Result.success(datasourceService.testSavedConnection(id));
    }

    @PostMapping("/{id}/access")
    public Result<Map<String, Integer>> grantAccess(@PathVariable Long id,
                                                    @Valid @RequestBody DatasourceAccessGrantDTO request) {
        int granted = accessService.grantAccess(id, request);
        return Result.success("授权成功", Map.of("granted", granted));
    }

    @GetMapping("/{id}/access")
    public Result<List<DatasourceAccessVO>> listAccess(@PathVariable Long id) {
        return Result.success(accessService.listAccess(id));
    }

    @DeleteMapping("/{id}/access/{userId}")
    public Result<Void> revokeAccess(@PathVariable Long id, @PathVariable Long userId) {
        accessService.revokeAccess(id, userId);
        return Result.success("撤销成功", null);
    }
}
