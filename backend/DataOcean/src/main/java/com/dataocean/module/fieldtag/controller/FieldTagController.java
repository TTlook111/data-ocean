package com.dataocean.module.fieldtag.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.annotation.IamS1ScopedList;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.fieldtag.entity.PredefinedTag;
import com.dataocean.module.fieldtag.entity.dto.BatchTagRequestDTO;
import com.dataocean.module.fieldtag.entity.dto.FieldTagRequestDTO;
import com.dataocean.module.fieldtag.entity.vo.FieldTagVO;
import com.dataocean.module.fieldtag.service.FieldTagService;
import com.dataocean.module.fieldtag.support.FieldGovernanceScopeSupport;
import com.dataocean.module.system.aspect.AdminAuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 字段标签管理控制器
 * <p>
 * 提供字段标签的增删查 API，支持单个打标和批量打标。
 * 需要 field-tag:manage 权限。
 * </p>
 */
@RestController
@RequestMapping("/api/field-tags")
@RequiredArgsConstructor
@AdminAuditLog
@Slf4j
public class FieldTagController {

    /** 查看字段标签。 */
    private static final String VIEW_FUNCTION = "governance:field:view";
    /** 维护字段标签：新增、批量、删除。 */
    private static final String MANAGE_FUNCTION = "governance:field:manage";

    private final FieldTagService fieldTagService;
    private final FieldGovernanceScopeSupport fieldScope;

    /**
     * 为字段添加标签
     *
     * @param request 打标请求参数
     * @return 创建的标签信息
     */
    @PostMapping
    @IamS1Resource(function = MANAGE_FUNCTION, resourceType = IamS1ResourceType.COLUMN_META,
            resourceIds = "#request.columnMetaId")
    public Result<FieldTagVO> addTag(@Valid @RequestBody FieldTagRequestDTO request) {
        return Result.success("打标成功", fieldTagService.addTag(request));
    }

    /**
     * 批量为多个字段打同一标签
     *
     * @param request 批量打标请求参数
     * @return 成功打标的数量
     */
    @PostMapping("/batch")
    @IamS1ScopedList(MANAGE_FUNCTION)
    public Result<Map<String, Integer>> batchAddTags(@Valid @RequestBody BatchTagRequestDTO request) {
        int count = fieldTagService.batchAddTags(request);
        return Result.success("批量打标成功", Map.of("tagged", count));
    }

    /**
     * 移除字段标签
     *
     * @param id 标签ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    @IamS1Resource(function = MANAGE_FUNCTION, resourceType = IamS1ResourceType.FIELD_TAG_RELATION, resourceIds = "#id")
    public Result<Void> removeTag(@PathVariable Long id) {
        fieldTagService.removeTag(id);
        return Result.success("移除成功", null);
    }

    /**
     * 查询指定字段的所有标签
     *
     * @param columnMetaId 字段元数据ID
     * @return 标签列表
     */
    @GetMapping("/column/{columnMetaId}")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.COLUMN_META, resourceIds = "#columnMetaId")
    public Result<List<FieldTagVO>> getTagsByColumn(@PathVariable Long columnMetaId) {
        return Result.success(fieldTagService.getTagsByColumnMetaId(columnMetaId));
    }

    /**
     * 按标签编码查询关联的字段ID列表
     *
     * @param tagCode 标签编码
     * @return 字段ID列表
     */
    @GetMapping("/by-tag/{tagCode}")
    @IamS1ScopedList(VIEW_FUNCTION)
    public Result<List<Long>> getColumnsByTag(@PathVariable String tagCode) {
        return Result.success(fieldTagService.getColumnIdsByTagCodeInDatasources(
                tagCode, fieldScope.visibleDatasourceIds(UserContext.currentUserId(), VIEW_FUNCTION)));
    }

    /**
     * 查询所有预定义标签
     *
     * @return 预定义标签列表
     */
    @GetMapping("/predefined")
    @IamS1ScopedList(VIEW_FUNCTION)
    public Result<List<PredefinedTag>> listPredefinedTags() {
        return Result.success(fieldTagService.listPredefinedTags());
    }
}
