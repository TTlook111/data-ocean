package com.dataocean.module.prompt.service.impl;
import com.dataocean.module.prompt.service.PromptInvocationService;
import com.dataocean.module.prompt.service.PromptSceneRegistry;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.prompt.entity.dto.PromptInvocationDTO;
import com.dataocean.module.prompt.entity.po.PromptInvocation;
import com.dataocean.module.prompt.entity.query.PromptInvocationQuery;
import com.dataocean.module.prompt.entity.vo.PromptInvocationVO;
import com.dataocean.module.prompt.mapper.PromptInvocationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
@Service
@RequiredArgsConstructor
public class PromptInvocationServiceImpl implements PromptInvocationService {
    private final PromptInvocationMapper mapper;
    public void record(PromptInvocationDTO request) {
        PromptSceneRegistry.require(request.getSceneCode());
        boolean managed = "MANAGED".equals(request.getSource());
        if ((managed && (request.getVersionNo() < 1 || !request.getFallbackReason().isEmpty()))
                || (!managed && (request.getVersionNo() != 0 || request.getFallbackReason().isEmpty()))) {
            throw new BusinessException(400, "策略版本与来源或回退原因不一致");
        }
        PromptInvocation row = new PromptInvocation();
        BeanUtils.copyProperties(request, row);
        row.setCreatedAt(LocalDateTime.now());
        try { mapper.insert(row); }
        catch (DuplicateKeyException duplicate) { /* 同一调用回执重传不重复计数。 */ }
    }
    public Page<PromptInvocationVO> list(PromptInvocationQuery query) {
        var wrapper = new LambdaQueryWrapper<PromptInvocation>()
            .eq(query.getSceneCode() != null && !query.getSceneCode().isBlank(), PromptInvocation::getSceneCode, query.getSceneCode())
            .orderByDesc(PromptInvocation::getId);
        Page<PromptInvocation> rows = mapper.selectPage(query.toMpPage(), wrapper);
        Page<PromptInvocationVO> result = new Page<>(rows.getCurrent(), rows.getSize(), rows.getTotal());
        result.setRecords(rows.getRecords().stream().map(row -> {
            PromptInvocationVO vo = new PromptInvocationVO();
            BeanUtils.copyProperties(row, vo);
            return vo;
        }).toList());
        return result;
    }
}
