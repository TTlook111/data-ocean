package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.dto.DepartmentProfileDTO;
import com.dataocean.module.iam.entity.po.DepartmentPO;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 部门与成员的持久化适配，业务状态和权限由所属服务核验。
 */
public interface DepartmentMapper {

    /**
     * 读取所属业务范围的完整当前记录，供服务在可信边界内核对。
     */
    @Select("SELECT * FROM iam_department ORDER BY sort_order,id")
    List<DepartmentPO> all();

    /**
     * 按稳定标识读取当前记录，由所属服务继续校验可见性和状态。
     */
    @Select("SELECT * FROM iam_department WHERE id=#{id}")
    DepartmentPO find(long id);

    /**
     * 领取当前配置或业务记录的排他锁，提交前重新核验权威事实。
     */
    @Select("SELECT * FROM iam_department WHERE id=#{id} FOR UPDATE")
    DepartmentPO lock(long id);

    /**
     * 在所属业务事务内保存明确类型记录。
     */
    @Insert(
            "INSERT INTO iam_department(parent_id,code,name,manager_user_id,note,sort_order,created_by,created_at,updated_at) VALUES(#{parent},#{d.code},#{d.name},#{manager},#{d.note},#{d.sortOrder},#{actor},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))")
    int insert(
            @Param("d") DepartmentProfileDTO data,
            @Param("parent") Long parent,
            @Param("manager") Long manager,
            @Param("actor") long actor);

    /**
     * 按当前对象版本原子更新业务资料并递增内容修订。
     */
    @Update(
            "UPDATE iam_department SET parent_id=#{parent},code=#{d.code},name=#{d.name},manager_user_id=#{manager},note=#{d.note},sort_order=#{d.sortOrder},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int profile(
            @Param("id") long id,
            @Param("d") DepartmentProfileDTO data,
            @Param("parent") Long parent,
            @Param("manager") Long manager);

    /**
     * 因当前关系或内容实际变化递增聚合修订和更新时间。
     */
    @Update("UPDATE iam_department SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int touch(long id);

    /**
     * 在组织配置锁内变更子部门父节点并递增其版本。
     */
    @Update(
            "UPDATE iam_department SET parent_id=#{parent},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int move(@Param("id") long id, @Param("parent") long parent);

    /**
     * 在所属事务中变更对象生命周期及真实状态时间。
     */
    @Update(
            "UPDATE iam_department SET lifecycle=#{lifecycle},access_review_required=CASE WHEN #{lifecycle}='ENABLED' THEN 1 ELSE access_review_required END,status_reason=#{reason},version=version+1,disabled_at=CASE WHEN #{lifecycle}='DISABLED' THEN UTC_TIMESTAMP(3) ELSE disabled_at END,archived_at=CASE WHEN #{lifecycle}='ARCHIVED' THEN UTC_TIMESTAMP(3) ELSE archived_at END,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int lifecycle(@Param("id") long id, @Param("lifecycle") String lifecycle, @Param("reason") String reason);

    /**
     * 统计人员仍担任负责人的非归档部门，供归档前关联核对。
     */
    @Select("SELECT COUNT(*) FROM iam_department WHERE manager_user_id=#{id} AND lifecycle!='ARCHIVED'")
    int managedBy(long id);

    /**
     * 按直属父节点在数据库内统计并分页读取子部门。
     */
    @Select("SELECT * FROM iam_department d WHERE d.parent_id=#{id}")
    Page<DepartmentPO> children(Page<DepartmentPO> page, @Param("id") long id);
}
