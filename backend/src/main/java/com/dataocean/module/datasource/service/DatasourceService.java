package com.dataocean.module.datasource.service;

import com.dataocean.module.datasource.entity.dto.ConnectionCheckDTO;
import com.dataocean.module.datasource.entity.dto.SourceCreateDTO;
import com.dataocean.module.datasource.entity.dto.SourceCreateProposalDTO;
import com.dataocean.module.datasource.entity.vo.ConnectionCheckVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;

/**
 * 数据源配置与能力测试的公开业务边界。
 */
public interface DatasourceService {

    /**
     * 执行分项测试，网络访问在平台库事务外执行。
     */
    ConnectionCheckVO test(Long source, String command, ConnectionCheckDTO input);

    /**
     * 按原操作者或已绑定源读取本次真实检查记录。
     */
    ConnectionCheckVO check(long checkId);

    /**
     * 确认拟新增的非秘密资料及目标生命周期。
     */
    ImpactPreviewVO reviewCreate(SourceCreateProposalDTO proposal);

    /**
     * 将已核对的资料保存为草稿或启用源。
     */
    MutationVO create(String command, SourceCreateDTO input);
}
