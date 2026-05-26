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
        int success = 0;
        int failed = 0;
        Long currentUserId = UserContext.currentUserId();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                // 跳过表头
                if (firstLine && (line.startsWith("column") || line.startsWith("字段"))) {
                    firstLine = false;
                    continue;
                }
                firstLine = false;
                // 解析 CSV 行
                String[] parts = line.split(",");
                if (parts.length < 2) {
                    failed++;
                    continue;
                }
                try {
                    Long columnId = Long.parseLong(parts[0].trim());
                    String tagCode = parts[1].trim();
                    // 校验标签编码
                    PredefinedTag predefined = predefinedTagMapper.selectOne(
                            new LambdaQueryWrapper<PredefinedTag>()
                                    .eq(PredefinedTag::getTagCode, tagCode)
                    );
                    if (predefined == null) {
                        failed++;
                        continue;
                    }
                    // 检查是否已存在
                    Long count = fieldTagMapper.selectCount(
                            new LambdaQueryWrapper<FieldTag>()
                                    .eq(FieldTag::getColumnMetaId, columnId)
                                    .eq(FieldTag::getTagCode, tagCode)
                    );
                    if (count > 0) {
                        failed++;
                        continue;
                    }
                    // 插入标签
                    FieldTag tag = new FieldTag();
                    tag.setColumnMetaId(columnId);
                    tag.setTagCode(tagCode);
                    tag.setTagName(predefined.getTagName());
                    tag.setSource("SYSTEM");
                    tag.setCreatedBy(currentUserId);
                    tag.setCreatedAt(LocalDateTime.now());
                    fieldTagMapper.insert(tag);
                    success++;
                } catch (NumberFormatException e) {
                    failed++;
                }
            }
        } catch (Exception e) {
            log.error("CSV 导入标签失败", e);
            throw new BusinessException("CSV 文件解析失败：" + e.getMessage());
        }
        log.info("CSV 导入标签完成 success={} failed={}", success, failed);
        return Map.of("success", success, "failed", failed);
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
