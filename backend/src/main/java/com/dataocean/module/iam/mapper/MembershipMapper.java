package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.iam.entity.po.MembershipPO;

import java.util.Collection;
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
        return forUsers(List.of(id));
    }

    /**
     * 按本页人员批量读取当前主兼任关系，保留每个人的主兼任展示顺序。
     */
    default List<MembershipPO> forUsers(Collection<Long> ids) {
        if (ids.isEmpty()) {
            // 空人员范围没有可读取的成员关系。
            return List.of();
        }

        // 保留 is_primary 的库列排序，布尔属性由 Lombok 暴露为 primary。
        return selectList(Wrappers.<MembershipPO>query()
                .orderByDesc("is_primary")
                .lambda()
                .in(MembershipPO::getUserId, ids)
                .isNull(MembershipPO::getLeftAt)
                .orderByAsc(MembershipPO::getId));
    }

    /**
     * 读取指定部门尚未结束的当前成员关系。
     */
    default List<MembershipPO> forDepartment(long id) {
        return forDepartments(List.of(id));
    }

    /**
     * 按给定部门范围批量读取当前成员关系，供部门统计按人员去重。
     */
    default List<MembershipPO> forDepartments(Collection<Long> ids) {
        if (ids.isEmpty()) {
            // 空部门范围没有成员。
            return List.of();
        }

        return selectList(Wrappers.<MembershipPO>lambdaQuery()
                .in(MembershipPO::getDepartmentId, ids)
                .isNull(MembershipPO::getLeftAt)
                .orderByAsc(MembershipPO::getUserId));
    }

    /**
     * 读取指定部门中尚未结束的成员关系。
     */
    default MembershipPO findInDepartment(long department, long id) {
        return selectOne(Wrappers.<MembershipPO>lambdaQuery()
                .eq(MembershipPO::getId, id)
                .eq(MembershipPO::getDepartmentId, department)
                .isNull(MembershipPO::getLeftAt));
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
