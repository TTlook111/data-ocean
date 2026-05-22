package com.dataocean.module.datasource.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.entity.vo.DatasourceSimpleVO;
import com.dataocean.module.datasource.service.DatasourceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据源用户端控制器
 * <p>
 * 提供普通用户（查询端）访问数据源相关的 API，
 * 当前仅包含获取用户可访问的数据源列表接口。
 * </p>
 *
 * @author dataocean
 */
@RestController
@RequestMapping("/api/datasources")
@RequiredArgsConstructor
public class DatasourceUserController {

    private final DatasourceAccessService accessService;

    /**
     * 获取当前登录用户可访问的数据源列表
     * <p>
     * 返回用户被授权且数据源已启用、未过期的简要信息列表，
     * 用于查询端选择数据源进行自然语言查询。
     * </p>
     *
     * @return 可访问的数据源简要信息列表
     */
    @GetMapping
    public Result<List<DatasourceSimpleVO>> listMyDatasources() {
        return Result.success(accessService.listAccessibleDatasources());
    }
}
