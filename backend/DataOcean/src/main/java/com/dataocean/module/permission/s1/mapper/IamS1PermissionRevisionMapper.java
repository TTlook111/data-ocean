package com.dataocean.module.permission.s1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.s1.entity.IamS1PermissionRevision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** IAM-SIMPLE-1 权限修订 Mapper。 */
@Mapper
public interface IamS1PermissionRevisionMapper extends BaseMapper<IamS1PermissionRevision> {

    @Select("SELECT MAX(revision_no) FROM iam_s1_permission_revision")
    Long selectCurrentRevision();
}
