package com.dataocean.module.access.service;

import com.dataocean.common.PageVO;
import com.dataocean.module.access.entity.dto.AccessRequestDTO;
import com.dataocean.module.access.entity.dto.RequestActionDTO;
import com.dataocean.module.access.entity.query.AccessListQuery;
import com.dataocean.module.access.entity.vo.AccessRequestVO;
import com.dataocean.module.access.entity.vo.RequestActivityVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;

import java.util.List;

/**
 * 本人申请及按当前责任审批的事务边界。
 */
public interface AccessRequestService {

    /**
     * 分页读取本人或实际办理范围。
     */
    PageVO<AccessRequestVO> list(AccessListQuery query, boolean mine);

    /**
     * 同一真实申请可按本人或受限办理视角回看。
     */
    AccessRequestVO detail(long id);

    /**
     * 只读回看当前人员实际处理过的子项，无当前待办操作。
     */
    AccessRequestVO handledDetail(long id);

    /**
     * 申请人回看已提交的全部历史活动。
     */
    List<RequestActivityVO> activities(long id);

    /**
     * 创建申请，或保存本人草稿／补充重提。
     */
    MutationVO save(Long id, String command, AccessRequestDTO input);

    /**
     * 本人核对草稿、首次提交或补充重提的实际分派范围。
     */
    ImpactPreviewVO previewSubmission(Long id, AccessRequestDTO input);

    /**
     * 运行唯一指定的申请动作，并校验对应的业务范围。
     */
    MutationVO act(long id, String command, RequestActionDTO input);

    /**
     * 读取当前可办理状态与规则，签发一次申请办理核对。
     */
    ImpactPreviewVO preview(long id, RequestActionDTO input);
}
