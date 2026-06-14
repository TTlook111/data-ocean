package com.dataocean.module.glossary.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dataocean.module.glossary.entity.Glossary;
import com.dataocean.module.glossary.mapper.GlossaryMapper;
import com.dataocean.module.glossary.service.GlossaryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 术语表服务实现
 *
 * @author dataocean
 */
@Slf4j
@Service
public class GlossaryServiceImpl extends ServiceImpl<GlossaryMapper, Glossary>
        implements GlossaryService {
}
