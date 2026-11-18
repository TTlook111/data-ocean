package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.dto.DepartmentProfileDTO;
import com.dataocean.module.iam.entity.po.DepartmentPO;

import java.util.List;
import java.util.Objects;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 部门与成员的持久化适配，业务状态和权限由所属服务核验。
 */
public interface DepartmentMapper extends BaseMapper<DepartmentPO> {

    /**
     * 读取所属业务范围的完整当前记录，供服务在可信边界内核对。
     */
    default List<DepartmentPO> all() {
        return selectList(
                Wrappers.<DepartmentPO>lambdaQuery().orderByAsc(DepartmentPO::getSortOrder, DepartmentPO::getId));
    }

    /**
     * 领取当前配置或业务记录的排他锁，提交前重新核验权威事实。
     */
    @Select("SELECT * FROM iam_department WHERE id=#{id} FOR UPDATE")
    DepartmentPO lock(long id);

    /**
     * 使用数据库时钟创建部门，并回填数据库生成的编号。
     */
    @Insert(
            """
            INSERT INTO iam_department(parent_id,code,name,manager_user_id,note,sort_order,created_by,created_at,updated_at)
            VALUES(#{parentId},#{code},#{name},#{managerUserId},#{note},#{sortOrder},#{createdBy},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCreated(DepartmentPO row);

    /**
     * 以已核验的父部门和负责人创建部门，状态及版本使用表默认值。
     */
    default long create(DepartmentProfileDTO data, Long parent, Long manager, long actor) {
        var row = new DepartmentPO();
        row.setParentId(parent);
        row.setCode(data.getCode());
        row.setName(data.getName());
        row.setManagerUserId(manager);
        row.setNote(data.getNote());
        row.setSortOrder(data.getSortOrder());
        row.setCreatedBy(actor);
        insertCreated(row);
        return Objects.requireNonNull(row.getId(), "数据库未返回部门编号");
    }

    /**
     * 按当前对象版本原子更新业务资料并递增内容修订。
     */
    default int profile(long id, DepartmentProfileDTO data, Long parent, Long manager) {
        return update(Wrappers.<DepartmentPO>lambdaUpdate()
                .eq(DepartmentPO::getId, id)
                .set(DepartmentPO::getParentId, parent)
                .set(DepartmentPO::getCode, data.getCode())
                .set(DepartmentPO::getName, data.getName())
                .set(DepartmentPO::getManagerUserId, manager)
                .set(DepartmentPO::getNote, data.getNote())
                .set(DepartmentPO::getSortOrder, data.getSortOrder())
                .setIncrBy(DepartmentPO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 因当前关系或内容实际变化递增聚合修订和更新时间。
     */
    default int touch(long id) {
        return update(Wrappers.<DepartmentPO>lambdaUpdate()
                .eq(DepartmentPO::getId, id)
                .setIncrBy(DepartmentPO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 在组织配置锁内变更子部门父节点并递增其版本。
     */
    default int move(long id, long parent) {
        return update(Wrappers.<DepartmentPO>lambdaUpdate()
                .eq(DepartmentPO::getId, id)
                .set(DepartmentPO::getParentId, parent)
                .setIncrBy(DepartmentPO::getVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 在所属事务中变更对象生命周期及真实状态时间。
     */
    @Update(
            "UPDATE iam_department SET lifecycle=#{lifecycle},access_review_required=CASE WHEN #{lifecycle}='ENABLED' THEN 1 ELSE access_review_required END,status_reason=#{reason},version=version+1,disabled_at=CASE WHEN #{lifecycle}='DISABLED' THEN UTC_TIMESTAMP(3) ELSE disabled_at END,archived_at=CASE WHEN #{lifecycle}='ARCHIVED' THEN UTC_TIMESTAMP(3) ELSE archived_at END,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int lifecycle(@Param("id") long id, @Param("lifecycle") String lifecycle, @Param("reason") String reason);

    /**
     * 核对人员是否仍担任任一非归档部门的负责人。
     */
    default boolean hasManagedDepartment(long id) {
        return exists(Wrappers.<DepartmentPO>lambdaQuery()
                .eq(DepartmentPO::getManagerUserId, id)
                .ne(DepartmentPO::getLifecycle, "ARCHIVED"));
    }

    /**
     * 按直属父节点在数据库内统计并分页读取子部门。
     */
    default Page<DepartmentPO> children(Page<DepartmentPO> page, long id) {
        return selectPage(page, Wrappers.<DepartmentPO>lambdaQuery().eq(DepartmentPO::getParentId, id));
    }
}
