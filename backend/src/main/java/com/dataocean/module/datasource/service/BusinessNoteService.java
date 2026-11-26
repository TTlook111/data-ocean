package com.dataocean.module.datasource.service;

import com.dataocean.common.PageVO;
import com.dataocean.module.datasource.entity.dto.BusinessNoteDTO;
import com.dataocean.module.datasource.entity.dto.BusinessNoteStateDTO;
import com.dataocean.module.datasource.entity.query.BusinessNoteQuery;
import com.dataocean.module.datasource.entity.vo.BusinessNoteDetailVO;
import com.dataocean.module.datasource.entity.vo.BusinessNoteRowVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;

import java.util.List;

/**
 * 企业业务说明、修订及同版附件的服务边界。
 */
public interface BusinessNoteService {

    /**
     * 每个修订允许保留与新增的总附件数。
     */
    int MAX_FILES = 5;

    /**
     * 原始文件只通过受限上传与下载使用。
     *
     * @param name 原始文件的安全名称
     * @param bytes 已通过入口大小限制的原始字节
     */
    record Attachment(String name, byte[] bytes) {}

    /**
     * 按资料范围、标题、人员和状态读取有效分页。
     */
    PageVO<BusinessNoteRowVO> list(BusinessNoteQuery query);

    /**
     * 读取当前或指定历史修订的原文及文件名称。
     */
    BusinessNoteDetailVO detail(long id, Integer revision);

    /**
     * 保存实际正文及附件变化，保留不可变修订。
     */
    MutationVO save(Long id, String command, BusinessNoteDTO input, List<Attachment> files);

    /**
     * 连接指定资料和修订后才读取原始文件内容。
     */
    Attachment download(long id, int revision, long attachment);

    /**
     * 核对当前资料生命周期的影响。
     */
    ImpactPreviewVO preview(long id, BusinessNoteStateDTO input);

    /**
     * 按核对执行撤下、恢复或删除。
     */
    MutationVO changeState(long id, String command, BusinessNoteStateDTO input);
}
