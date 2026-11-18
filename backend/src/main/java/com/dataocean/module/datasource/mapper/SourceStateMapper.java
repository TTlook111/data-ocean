package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.PageQuery;
import com.dataocean.module.datasource.entity.po.CollectionSchedulePO;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.mapper.CollectionScheduleMapper.ScheduleRow;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.mapper.UserMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 处理数据源查询、运行配置指针及定时接收游标。
 */
@Repository
@RequiredArgsConstructor
public class SourceStateMapper {

    /**
     * 读取可作为数据源联系人的账号投影。
     */
    private final UserMapper users;

    /**
     * 访问来源选项和单表生命周期字段。
     */
    private final DatasourceRecordMapper records;

    /**
     * 按来源身份更新计划游标。
     */
    private final CollectionScheduleMapper schedules;

    /**
     * 目录和采集记录只需源编号、名字及生命周期。
     */
    public List<DatasourcePO> options() {
        return records.selectList(Wrappers.<DatasourcePO>lambdaQuery()
                .select(
                        DatasourcePO::getId,
                        DatasourcePO::getName,
                        DatasourcePO::getLifecycle,
                        DatasourcePO::getVersion)
                .orderByAsc(DatasourcePO::getName, DatasourcePO::getId));
    }

    /**
     * 生命周期切换不生成新连接快照。
     */
    public void lifecycle(long source, String state) {
        records.update(Wrappers.<DatasourcePO>lambdaUpdate()
                .eq(DatasourcePO::getId, source)
                .set(DatasourcePO::getLifecycle, state)
                .setIncrBy(DatasourcePO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 暂停只影响未来自动采集。
     */
    public void pause(long source, boolean paused) {
        records.update(Wrappers.<DatasourcePO>lambdaUpdate()
                .eq(DatasourcePO::getId, source)
                .set(DatasourcePO::isSchedulePaused, paused)
                .setIncrBy(DatasourcePO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 读取定时信息，尚未首次成功时计划锚点为空。
     */
    public ScheduleRow schedule(long source) {
        return Optional.ofNullable(schedules.schedule(source)).orElseThrow();
    }

    /**
     * 找出到期启用源，计划接收在对应源事务内复核。
     */
    public List<Long> due() {
        return schedules.due();
    }

    /**
     * 定时接收成功时前进游标，暂未接收只调整下次检查时点。
     */
    public void cursor(long source, long cycle, LocalDateTime next) {
        schedules.update(Wrappers.<CollectionSchedulePO>lambdaUpdate()
                .eq(CollectionSchedulePO::getSourceId, source)
                .set(CollectionSchedulePO::getLastAdmittedCycleNo, cycle)
                .set(CollectionSchedulePO::getNextCheckAt, next)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 开始新的陈旧区间时刷新数据源更新时间，后续检查沿用原值。
     */
    public boolean stale(long source, byte[] scope) {
        return records.update(Wrappers.<DatasourcePO>lambdaUpdate()
                        .eq(DatasourcePO::getId, source)
                        .isNull(DatasourcePO::getStaleSince)
                        .set(DatasourcePO::getStaleScopeKey, scope)
                        .setSql("stale_since=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3)"))
                == 1;
    }

    /**
     * 验证新选联系人的账号仍处于正常生命周期。
     */
    public boolean validContact(long id) {
        return users.exists(Wrappers.<UserPO>lambdaQuery().eq(UserPO::getId, id).eq(UserPO::getLifecycle, "ENABLED"));
    }

    /**
     * 保留登录名和姓名的字面包含搜索，并只读取联系人选项所需字段。
     */
    public Page<UserPO> contacts(String keyword, PageQuery paging) {
        var filters = Wrappers.<UserPO>lambdaQuery()
                .eq(UserPO::getLifecycle, "ENABLED")
                .apply("(LOCATE({0}, username) > 0 OR LOCATE({0}, display_name) > 0)", keyword);

        long total = users.selectCount(filters);
        var page = paging.<UserPO>toLastAvailableMpPage(total);
        return users.selectPage(
                page,
                filters.select(
                                UserPO::getId,
                                UserPO::getUsername,
                                UserPO::getDisplayName,
                                UserPO::getLifecycle,
                                UserPO::getVersion)
                        .orderByAsc(UserPO::getDisplayName, UserPO::getId));
    }
}
