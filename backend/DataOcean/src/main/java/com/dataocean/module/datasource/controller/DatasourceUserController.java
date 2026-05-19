package com.dataocean.module.datasource.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.entity.vo.DatasourceSimpleVO;
import com.dataocean.module.datasource.service.DatasourceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/datasources")
@RequiredArgsConstructor
public class DatasourceUserController {

    private final DatasourceAccessService accessService;

    @GetMapping
    public Result<List<DatasourceSimpleVO>> listMyDatasources() {
        return Result.success(accessService.listAccessibleDatasources());
    }
}
