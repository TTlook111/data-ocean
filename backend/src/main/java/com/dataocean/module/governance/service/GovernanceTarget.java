package com.dataocean.module.governance.service;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.module.governance.entity.vo.GovernanceTargetVO;

/**
 * 稳定条目键，表说明和字段各自维护确认。
 *
 * @param source 数据源编号
 * @param object 对象稳定编号
 * @param kind 条目类别
 * @param target 对象或字段稳定编号
 */
public record GovernanceTarget(long source, long object, String kind, long target) {

    /**
     * 核对客户端路径所表达的目标类别和归属。
     */
    public static GovernanceTarget parse(long source, long object, String kind, String target) {
        long id = Ids.parse(target);
        if (!"COLUMN".equals(kind) && !("TABLE".equals(kind) && object == id)) {
            // 表说明只能落在所属对象稳定身份上。
            throw BizIllegalException.missing();
        }
        return new GovernanceTarget(source, object, kind, id);
    }

    /**
     * 生成一个真实表说明的稳定目标。
     */
    public static GovernanceTarget table(long source, long object) {
        return new GovernanceTarget(source, object, "TABLE", object);
    }

    /**
     * 生成一个真实字段的稳定目标。
     */
    public static GovernanceTarget column(long source, long object, long field) {
        return new GovernanceTarget(source, object, "COLUMN", field);
    }

    /**
     * 按公共契约输出字符串标识。
     */
    public GovernanceTargetVO view() {
        return new GovernanceTargetVO(Long.toString(object), kind, Long.toString(target));
    }

    /**
     * 判断业务内容格式是否为字段定义。
     */
    public boolean isColumn() {
        return "COLUMN".equals(kind);
    }

    /**
     * 用不可混淆的非人员目标键加入公共命令指纹。
     */
    public String commandTarget() {
        return "governance:" + source + "/" + object + "/" + kind + "/" + target;
    }
}
