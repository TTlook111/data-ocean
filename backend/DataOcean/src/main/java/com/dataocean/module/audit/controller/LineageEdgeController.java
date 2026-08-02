package com.dataocean.module.audit.controller;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.result.Result;
import com.dataocean.module.audit.entity.dto.LineageCreateRequest;
import com.dataocean.module.audit.entity.vo.LineageEdgeVO;
import com.dataocean.module.audit.entity.vo.LineageGraphVO;
import com.dataocean.module.audit.service.LineageEdgeService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 血缘边管理控制器
 * <p>
 * 提供 ETL/MANUAL 血缘的手动创建、删除、批量导入和增强查询 API。
 * 所有管理类 API 需要 metadata:manage 权限。
 * 路径前缀 /api/admin/catalog/lineage，与文档 data-lineage-research.md §4.1.1 一致。
 * </p>
 *
 * @author dataocean
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/catalog/lineage")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('metadata:manage', '*')")
public class LineageEdgeController {

    private final LineageEdgeService lineageEdgeService;
    private final ObjectMapper objectMapper;

    // ========== 创建 LINEAGE 关系 ==========

    /**
     * 创建单条 LINEAGE 关系
     * <p>
     * 创建 TABLE → TABLE 的表级血缘边，如果提供了 columnMappings 则自动生成对应的 DERIVED_FROM 列级边。
     * 校验源/目标实体类型均为 TABLE，拒绝直接创建 COLUMN → COLUMN 的 LINEAGE。
     * </p>
     *
     * @param request 血缘创建请求体
     * @return 创建结果
     */
    @PostMapping
    public Result<LineageEdgeVO> createLineage(@Valid @RequestBody LineageCreateRequest request) {
        LineageEdgeVO vo = lineageEdgeService.createLineage(request);
        return Result.success("血缘关系创建成功", vo);
    }

    // ========== 删除 LINEAGE 关系 ==========

    /**
     * 删除 LINEAGE 关系
     * <p>
     * cascadeDerived=true 时级联删除关联的 DERIVED_FROM 边；
     * cascadeDerived=false 或未传时仅删除 LINEAGE 边，DERIVED_FROM 保留为孤边。
     * 响应中包含关联的 DERIVED_FROM 边数量，前端可用于弹窗提示。
     * </p>
     *
     * @param relationshipId 关系主键 ID
     * @param cascadeDerived 是否级联删除关联的 DERIVED_FROM 边
     * @return 操作结果
     */
    @DeleteMapping("/{relationshipId}")
    public Result<Map<String, Object>> deleteLineage(
            @PathVariable Long relationshipId,
            @RequestParam(defaultValue = "false") boolean cascadeDerived) {
        int derivedCount = lineageEdgeService.deleteLineage(relationshipId, cascadeDerived);
        if (derivedCount > 0 && !cascadeDerived) {
            // 有关联列映射但未级联删除，提示前端弹窗确认
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("relationshipId", relationshipId);
            result.put("derivedCount", derivedCount);
            result.put("message", "该关系下有 " + derivedCount + " 条列映射，DERIVED_FROM 边已保留为孤边");
            return Result.success(result);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("relationshipId", relationshipId);
        result.put("derivedCount", derivedCount);
        result.put("deleted", true);
        return Result.success("血缘关系已删除", result);
    }

    // ========== 批量创建血缘 ==========

    /**
     * 批量创建血缘（支持 CSV/JSON 文件导入）
     * <p>
     * CSV 格式（表级血缘）：source_table,target_table,lineage_type,description<br>
     * JSON 格式（含列映射）：数组，每项结构与单条创建请求体一致
     * </p>
     *
     * @param file 上传文件（.csv 或 .json）
     * @return 批量创建结果
     */
    @PostMapping("/batch")
    public Result<List<LineageEdgeVO>> batchCreateLineage(@RequestParam MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error(400, "上传文件不能为空");
        }

        List<LineageCreateRequest> requests;
        String filename = file.getOriginalFilename();
        try {
            if (filename != null && filename.toLowerCase().endsWith(".csv")) {
                requests = parseCsvFile(file);
            } else if (filename != null && filename.toLowerCase().endsWith(".json")) {
                requests = parseJsonFile(file);
            } else {
                return Result.error(400, "仅支持 .csv 或 .json 文件格式");
            }
        } catch (Exception e) {
            log.error("解析批量导入文件失败 filename={}", filename, e);
            return Result.error(400, "文件解析失败: " + e.getMessage());
        }

        if (requests.isEmpty()) {
            return Result.error(400, "文件中无有效数据");
        }

        List<LineageEdgeVO> results = lineageEdgeService.batchCreateLineage(requests);
        return Result.success("批量导入完成", results);
    }

    // ========== 文件解析辅助方法 ==========

    /**
     * 解析 CSV 文件
     * <p>
     * CSV 格式：source_entity_id,target_entity_id,lineage_type,description
     * （CSV 仅支持按实体 ID 导入表级血缘，不支持列映射）
     * </p>
     */
    private List<LineageCreateRequest> parseCsvFile(MultipartFile file) throws Exception {
        List<LineageCreateRequest> requests = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                // 跳过空行和标题行
                if (line.isEmpty() || line.toLowerCase().startsWith("source")) continue;

                String[] parts = line.split(",", 4);
                if (parts.length < 3) {
                    log.warn("CSV 第 {} 行格式不正确，已跳过: {}", lineNum, line);
                    continue;
                }

                LineageCreateRequest req = new LineageCreateRequest();
                req.setSourceEntityId(Long.parseLong(parts[0].trim()));
                req.setTargetEntityId(Long.parseLong(parts[1].trim()));
                req.setLineageType(parts[2].trim().toUpperCase());
                if (parts.length > 3 && !parts[3].isBlank()) {
                    req.setDescription(parts[3].trim());
                }
                requests.add(req);
            }
        }
        return requests;
    }

    /**
     * 解析 JSON 文件
     * <p>
     * JSON 格式：数组，每项结构与单条创建请求体一致，支持列映射。
     * </p>
     */
    private List<LineageCreateRequest> parseJsonFile(MultipartFile file) throws Exception {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return objectMapper.readValue(content, new TypeReference<>() {});
    }
}
