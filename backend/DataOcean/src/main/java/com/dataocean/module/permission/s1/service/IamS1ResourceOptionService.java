package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.IamS1SnapshotOption;
import com.dataocean.module.permission.s1.entity.vo.IamS1ColumnOptionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1TableOptionVO;

import java.util.List;

/**
 * IAM-SIMPLE-1 授权与字段保护表单的资源选项读取。
 * <p>
 * 只读取已发布元数据快照的表字段资源事实；表字段中文名取自已审核元数据注释，
 * 缺少业务名称时显示物理名，不根据字段名猜业务含义。
 * </p>
 */
public interface IamS1ResourceOptionService {

    /** 该数据源已发布的元数据快照。 */
    List<IamS1SnapshotOption> publishedSnapshots(Long operatorUserId, Long datasourceId);

    /** 快照中可进入授权表单的表。 */
    List<IamS1TableOptionVO> tables(Long operatorUserId, Long datasourceId, Long snapshotId);

    /** 表字段选项，含当前字段保护状态。 */
    List<IamS1ColumnOptionVO> columns(Long operatorUserId, Long datasourceId, Long snapshotId, String tableName);
}
