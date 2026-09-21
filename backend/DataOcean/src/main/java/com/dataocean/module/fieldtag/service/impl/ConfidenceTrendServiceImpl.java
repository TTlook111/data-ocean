package com.dataocean.module.fieldtag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.fieldtag.entity.FieldConfidence;
import com.dataocean.module.fieldtag.entity.FieldConfidenceEvent;
import com.dataocean.module.fieldtag.entity.FieldTag;
import com.dataocean.module.fieldtag.entity.PredefinedTag;
import com.dataocean.module.fieldtag.entity.vo.ConfidenceTrendPointVO;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceEventMapper;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceMapper;
import com.dataocean.module.fieldtag.mapper.FieldTagMapper;
import com.dataocean.module.fieldtag.mapper.PredefinedTagMapper;
import com.dataocean.module.fieldtag.service.ConfidenceTrendService;
import com.dataocean.module.fieldtag.support.FieldGovernanceScopeSupport;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 可信度趋势与批量操作服务实现类
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfidenceTrendServiceImpl implements ConfidenceTrendService {

    private final FieldConfidenceEventMapper eventMapper;
    private final FieldConfidenceMapper confidenceMapper;
    private final FieldTagMapper fieldTagMapper;
    private final PredefinedTagMapper predefinedTagMapper;
    private final DbColumnMetaMapper dbColumnMetaMapper;
    private final FieldGovernanceScopeSupport fieldScope;

    /** 自动打标规则：字段名后缀 → 标签编码 */
    private static final Map<String, String> AUTO_TAG_RULES = Map.of(
            "_amount", "AMOUNT",
            "_price", "AMOUNT",
            "_fee", "AMOUNT",
            "_cost", "AMOUNT",
            "_time", "TIME",
            "_date", "TIME",
            "_at", "TIME",
            "_status", "STATUS",
            "_state", "STATUS"
    );

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ConfidenceTrendPointVO> getTrend(Long columnMetaId, int days) {
        LocalDateTime startTime = LocalDateTime.now().minusDays(days);
        // 查询事件列表
        List<FieldConfidenceEvent> events = eventMapper.selectList(
                new LambdaQueryWrapper<FieldConfidenceEvent>()
                        .eq(FieldConfidenceEvent::getColumnMetaId, columnMetaId)
                        .ge(FieldConfidenceEvent::getCreatedAt, startTime)
                        .orderByAsc(FieldConfidenceEvent::getCreatedAt)
        );
        // 获取当前分数用于反推历史分数
        FieldConfidence current = confidenceMapper.selectOne(
                new LambdaQueryWrapper<FieldConfidence>()
                        .eq(FieldConfidence::getColumnMetaId, columnMetaId)
        );
        int currentScore = current != null ? current.getScore() : 0;
        // 从当前分数反推每个事件点的累计分数
        // 先计算所有事件的总 delta
        int totalDelta = events.stream().mapToInt(FieldConfidenceEvent::getDeltaScore).sum();
        int baseScore = currentScore - totalDelta;
        // 构建趋势数据
        List<ConfidenceTrendPointVO> trendPoints = new ArrayList<>();
        int cumulative = baseScore;
        for (FieldConfidenceEvent event : events) {
            cumulative += event.getDeltaScore();
            cumulative = Math.max(0, Math.min(100, cumulative));
            ConfidenceTrendPointVO point = new ConfidenceTrendPointVO();
            point.setTime(event.getCreatedAt());
            point.setDeltaScore(event.getDeltaScore());
            point.setEventType(event.getEventType());
            point.setCumulativeScore(cumulative);
            trendPoints.add(point);
        }
        return trendPoints;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Integer> importTagsFromCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("CSV 文件不能为空");
        }
        if (file.getSize() > FieldGovernanceScopeSupport.MAX_CSV_BYTES) {
            throw new BusinessException(400, "CSV 文件不能超过 1MB");
        }
        List<CsvTagRow> rows;
        try {
            rows = parseTagCsv(file);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("CSV 导入标签失败", e);
            throw new BusinessException("CSV 文件解析失败：" + e.getMessage());
        }
        if (rows.isEmpty()) {
            throw new BusinessException(400, "文件中无有效数据");
        }
        if (rows.size() > FieldGovernanceScopeSupport.MAX_BATCH_COLUMNS) {
            throw new BusinessException(400, "单次最多导入 "
                    + FieldGovernanceScopeSupport.MAX_BATCH_COLUMNS + " 行，当前: " + rows.size());
        }

        List<Long> columnIds = rows.stream().map(CsvTagRow::columnId).toList();
        // 先完整读取归属并逐源校验：任一无权、不存在或断链即整批零写入。
        fieldScope.requireColumnsWritable(columnIds);

        Map<String, PredefinedTag> tagsByCode = new HashMap<>();
        for (CsvTagRow row : rows) {
            PredefinedTag predefined = tagsByCode.computeIfAbsent(row.tagCode(), this::loadPredefinedTag);
            if (predefined == null) {
                throw new BusinessException(400, "第 " + row.line() + " 行标签编码无效：" + row.tagCode());
            }
        }

        Long currentUserId = UserContext.currentUserId();
        int success = 0;
        int skipped = 0;
        for (CsvTagRow row : rows) {
            PredefinedTag predefined = tagsByCode.get(row.tagCode());
            Long count = fieldTagMapper.selectCount(
                    new LambdaQueryWrapper<FieldTag>()
                            .eq(FieldTag::getColumnMetaId, row.columnId())
                            .eq(FieldTag::getTagCode, row.tagCode())
            );
            if (count != null && count > 0) {
                skipped++;
                continue;
            }
            FieldTag tag = new FieldTag();
            tag.setColumnMetaId(row.columnId());
            tag.setTagCode(row.tagCode());
            tag.setTagName(predefined.getTagName());
            tag.setSource("SYSTEM");
            tag.setCreatedBy(currentUserId);
            tag.setCreatedAt(LocalDateTime.now());
            fieldTagMapper.insert(tag);
            success++;
        }
        log.info("CSV 导入标签完成 success={} skipped={}", success, skipped);
        return Map.of("success", success, "skipped", skipped);
    }

    private List<CsvTagRow> parseTagCsv(MultipartFile file) throws Exception {
        List<CsvTagRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean firstLine = true;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (firstLine && (line.toLowerCase().startsWith("column") || line.startsWith("字段"))) {
                    firstLine = false;
                    continue;
                }
                firstLine = false;
                String[] parts = line.split(",", 2);
                if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    throw new BusinessException(400, "第 " + lineNumber + " 行格式不正确，需要 column_id,tag_code");
                }
                try {
                    rows.add(new CsvTagRow(lineNumber, Long.parseLong(parts[0].trim()), parts[1].trim()));
                } catch (NumberFormatException e) {
                    throw new BusinessException(400, "第 " + lineNumber + " 行字段 ID 不是数字");
                }
            }
        }
        return rows;
    }

    private PredefinedTag loadPredefinedTag(String tagCode) {
        return predefinedTagMapper.selectOne(
                new LambdaQueryWrapper<PredefinedTag>().eq(PredefinedTag::getTagCode, tagCode));
    }

    private record CsvTagRow(int line, Long columnId, String tagCode) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Integer> autoTagByPattern(Long datasourceId) {
        // 查询该数据源下所有字段
        List<DbColumnMeta> columns = dbColumnMetaMapper.selectList(
                new LambdaQueryWrapper<DbColumnMeta>()
                        .eq(DbColumnMeta::getDatasourceId, datasourceId)
        );
        int tagged = 0;
        int skipped = 0;
        Long currentUserId = UserContext.currentUserId();
        for (DbColumnMeta column : columns) {
            String columnName = column.getColumnName().toLowerCase();
            // 匹配规则
            String matchedTagCode = null;
            for (Map.Entry<String, String> rule : AUTO_TAG_RULES.entrySet()) {
                if (columnName.endsWith(rule.getKey())) {
                    matchedTagCode = rule.getValue();
                    break;
                }
            }
            if (matchedTagCode == null) {
                skipped++;
                continue;
            }
            // 检查是否已存在
            Long count = fieldTagMapper.selectCount(
                    new LambdaQueryWrapper<FieldTag>()
                            .eq(FieldTag::getColumnMetaId, column.getId())
                            .eq(FieldTag::getTagCode, matchedTagCode)
            );
            if (count > 0) {
                skipped++;
                continue;
            }
            // 获取标签名称
            PredefinedTag predefined = predefinedTagMapper.selectOne(
                    new LambdaQueryWrapper<PredefinedTag>()
                            .eq(PredefinedTag::getTagCode, matchedTagCode)
            );
            if (predefined == null) {
                skipped++;
                continue;
            }
            // 插入标签
            FieldTag tag = new FieldTag();
            tag.setColumnMetaId(column.getId());
            tag.setTagCode(matchedTagCode);
            tag.setTagName(predefined.getTagName());
            tag.setSource("SYSTEM");
            tag.setCreatedBy(currentUserId);
            tag.setCreatedAt(LocalDateTime.now());
            fieldTagMapper.insert(tag);
            tagged++;
        }
        log.info("自动打标完成 datasourceId={} tagged={} skipped={}", datasourceId, tagged, skipped);
        return Map.of("tagged", tagged, "skipped", skipped);
    }
}
