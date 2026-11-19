package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.po.NoteAttachmentPO;

/**
 * 存储原文件和稳定身份，普通列表排除内容。
 */
public interface NoteAttachmentMapper extends BaseMapper<NoteAttachmentPO> {}
