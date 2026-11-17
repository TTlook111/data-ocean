package com.dataocean.module.iam.mapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.dto.DepartmentProfileDTO;
import com.dataocean.module.iam.entity.po.DepartmentPO;


public interface DepartmentMapper {
    @Select("SELECT * FROM iam_department ORDER BY sort_order,id") List<DepartmentPO> all();
    @Select("SELECT * FROM iam_department WHERE id=#{id}") DepartmentPO find(long id);
    @Select("SELECT * FROM iam_department WHERE id=#{id} FOR UPDATE") DepartmentPO lock(long id);
    @Insert("INSERT INTO iam_department(parent_id,code,name,manager_user_id,note,sort_order,created_by,created_at,updated_at) VALUES(#{parent},#{d.code},#{d.name},#{manager},#{d.note},#{d.sortOrder},#{actor},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))")
    int insert(@Param("d") DepartmentProfileDTO data,@Param("parent") Long parent,@Param("manager") Long manager,@Param("actor") long actor);
    @Update("UPDATE iam_department SET parent_id=#{parent},code=#{d.code},name=#{d.name},manager_user_id=#{manager},note=#{d.note},sort_order=#{d.sortOrder},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int profile(@Param("id") long id,@Param("d") DepartmentProfileDTO data,@Param("parent") Long parent,@Param("manager") Long manager);
    @Update("UPDATE iam_department SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}") int touch(long id);
    @Update("UPDATE iam_department SET parent_id=#{parent},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}") int move(@Param("id") long id,@Param("parent") long parent);
    @Update("UPDATE iam_department SET lifecycle=#{lifecycle},access_review_required=CASE WHEN #{lifecycle}='ENABLED' THEN 1 ELSE access_review_required END,status_reason=#{reason},version=version+1,disabled_at=CASE WHEN #{lifecycle}='DISABLED' THEN UTC_TIMESTAMP(3) ELSE disabled_at END,archived_at=CASE WHEN #{lifecycle}='ARCHIVED' THEN UTC_TIMESTAMP(3) ELSE archived_at END,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}")
    int lifecycle(@Param("id") long id,@Param("lifecycle") String lifecycle,@Param("reason") String reason);
    @Select("SELECT COUNT(*) FROM iam_department WHERE manager_user_id=#{id} AND lifecycle!='ARCHIVED'") int managedBy(long id);
}
