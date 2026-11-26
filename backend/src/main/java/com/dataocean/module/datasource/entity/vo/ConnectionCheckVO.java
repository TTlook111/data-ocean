package com.dataocean.module.datasource.entity.vo;

import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceProbe.Item;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 可回看的分项连接测试。所有秘密和内部关联摘要均留在平台库。
 */
@Data
@AllArgsConstructor
public class ConnectionCheckVO {

    /**
     * 留给后续核对或正式提交的检查编号。
     */
    private String checkId;

    /**
     * 已绑定的源编号，未保存数据源时为空。
     */
    private String sourceId;

    /**
     * 本次测试记录的创建时间，UTC。
     */
    private String checkedAt;

    /**
     * 检查可以用于新操作的截止时间，UTC。
     */
    private String expiresAt;

    /**
     * 实际源库报告的数据库版本。
     */
    private String detectedVersion;

    /**
     * PostgreSQL 可供选择的 Schema。
     */
    private List<String> availableSchemas;

    /**
     * 本次测试实际枚举的 Schema。
     */
    private List<String> verifiedSchemas;

    /**
     * 连接测试抽查结构的对象，不包含业务行。
     */
    private List<ObjectRef> coverage;

    /**
     * 按原型顺序呈现的测试结果。
     */
    private List<Item> items;

    /**
     * 应用的读取账号安全要求是否满足。
     */
    private String readOnlySafety;

    /**
     * 业务行读取能力的单独结论。
     */
    private String businessReadStatus;

    /**
     * 当前可用证据是否达到启用条件。
     */
    private boolean canEnable;
}
