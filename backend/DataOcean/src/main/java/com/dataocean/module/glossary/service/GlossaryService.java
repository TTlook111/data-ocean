package com.dataocean.module.glossary.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dataocean.module.glossary.entity.Glossary;

/**
 * 术语表服务接口
 *
 * @author dataocean
 */
public interface GlossaryService extends IService<Glossary> {

    /**
     * 删除术语表，并级联清理其下术语及其关联数据。
     * <p>
     * 原实现只做单表 `removeById`，而相关表之间没有外键约束，会静默产生孤儿数据：
     * 其下术语的 `fqn` 仍占用 `uk_term_fqn` 唯一键，导致重建同名术语表因 FQN 冲突而失败。
     * </p>
     *
     * @param glossaryId 术语表 ID
     * @return 同时删除的术语数量
     */
    int deleteGlossary(Long glossaryId);
}
