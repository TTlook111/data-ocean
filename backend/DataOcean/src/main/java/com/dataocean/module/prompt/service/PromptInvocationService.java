package com.dataocean.module.prompt.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.prompt.entity.dto.PromptInvocationDTO;
import com.dataocean.module.prompt.entity.query.PromptInvocationQuery;
import com.dataocean.module.prompt.entity.vo.PromptInvocationVO;

/** 实际模型调用回执与分页查询。 */
public interface PromptInvocationService {
    void record(PromptInvocationDTO request);
    Page<PromptInvocationVO> list(PromptInvocationQuery query);
}
