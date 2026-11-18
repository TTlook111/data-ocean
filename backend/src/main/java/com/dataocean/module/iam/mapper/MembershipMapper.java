package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.iam.entity.po.MembershipPO;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 公共契约的持久化适配，业务状态和权限由所属服务核验。
 */
public interface MembershipMapper extends BaseMapper<MembershipPO> {

    /**
     * 一次读取当前关系，用于组织树各层去重统计，避免逐节点查询。
     */
    default List<MembershipPO> current() {
        return selectList(Wrappers.<MembershipPO>lambdaQuery()
                .isNull(MembershipPO::getLeftAt)
                .orderByAsc(MembershipPO::getDepartmentId, MembershipPO::getUserId));
    }

    /**
     * 读取指定人员尚未结束的主兼任关系。
     */
    default List<MembershipPO> forUser(long id) {
        // 保留 is_primary 的库列排序，布尔属性由 Lombok 暴露为 primary。
        return selectList(Wrappers.<MembershipPO>query()
                .orderByDesc("is_primary")
                .lambda()
                .eq(MembershipPO::getUserId, id)
                .isNull(MembershipPO::getLeftAt)
                .orderByAsc(MembershipPO::getId));
    }

    /**
     * 读取指定部门尚未结束的当前成员关系。
     */
    default List<MembershipPO> forDepartment(long id) {
        return selectList(Wrappers.<MembershipPO>lambdaQuery()
                .eq(MembershipPO::getDepartmentId, id)
                .isNull(MembershipPO::getLeftAt)
                .orderByAsc(MembershipPO::getUserId));
    }

    /**
     * 按稳定标识读取当前记录，由所属服务继续校验可见性和状态。
     */
    default MembershipPO find(long id) {
        return selectOne(
                Wrappers.<MembershipPO>lambdaQuery().eq(MembershipPO::getId, id).isNull(MembershipPO::getLeftAt));
    }

    /**
     * 建立一条明确主兼任性质的当前成员关系。
     */
    @Insert(
            "INSERT INTO iam_user_department(user_id,department_id,is_primary,joined_by,joined_at) VALUES(#{user},#{department},#{primary},#{actor},UTC_TIMESTAMP(3))")
    int add(
            @Param("user") long user,
            @Param("department") long department,
            @Param("primary") boolean primary,
            @Param("actor") long actor);

    /**
     * 结束当前成员关系并保留真实结束人员和时间。
     */
    default int end(long id, long actor) {
        return update(Wrappers.<MembershipPO>lambdaUpdate()
                .eq(MembershipPO::getId, id)
                .isNull(MembershipPO::getLeftAt)
                .set(MembershipPO::getLeftBy, actor)
                .setSql("left_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 结束人员当前关联关系，用于归档事务而非历史硬删除。
     */
    default int endUser(long user, long actor) {
        return update(Wrappers.<MembershipPO>lambdaUpdate()
                .eq(MembershipPO::getUserId, user)
                .isNull(MembershipPO::getLeftAt)
                .set(MembershipPO::getLeftBy, actor)
                .setSql("left_at=UTC_TIMESTAMP(3)"));
    }
}
