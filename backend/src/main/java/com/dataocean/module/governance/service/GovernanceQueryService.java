package com.dataocean.module.governance.service;

import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.module.governance.entity.query.GovernanceColumnQuery;
import com.dataocean.module.governance.entity.query.GovernanceHistoryQuery;
import com.dataocean.module.governance.entity.query.GovernanceTableQuery;
import com.dataocean.module.governance.entity.vo.GovernanceColumnRowVO;
import com.dataocean.module.governance.entity.vo.GovernanceHistoryRowVO;
import com.dataocean.module.governance.entity.vo.GovernanceItemVO;
import com.dataocean.module.governance.entity.vo.GovernanceNoteChangeVO;
import com.dataocean.module.governance.entity.vo.GovernanceObjectVO;
import com.dataocean.module.governance.entity.vo.GovernanceTablePageVO;

/**
 * 工作台当前目录、治理定义和复核的只读入口。
 */
public interface GovernanceQueryService {

    /**
     * 同一成功目录内分页查找表和视图。
     */
    GovernanceTablePageVO tables(long source, GovernanceTableQuery query);

    /**
     * 按稳定对象取得表说明及来源影响。
     */
    GovernanceObjectVO object(long source, long object);

    /**
     * 在当前固定对象修订内读取字段。
     */
    PageVO<GovernanceColumnRowVO> columns(long source, long object, GovernanceColumnQuery query);

    /**
     * 读取可披露的当前条目。
     */
    GovernanceItemVO item(GovernanceTarget target);

    /**
     * 分页回看合法历史修订和审核记录。
     */
    PageVO<GovernanceHistoryRowVO> history(GovernanceTarget target, GovernanceHistoryQuery query);

    /**
     * 阅读同源未定位资料变化，或按有读取权的具体资料回看已产生影响。
     */
    PageVO<GovernanceNoteChangeVO> noteChanges(long source, Long note, PageQuery query);
}
