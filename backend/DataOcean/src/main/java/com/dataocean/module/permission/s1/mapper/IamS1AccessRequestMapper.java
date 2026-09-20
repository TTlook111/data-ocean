package com.dataocean.module.permission.s1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.s1.entity.IamS1AccessRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** IAM-SIMPLE-1 访问申请 Mapper。只访问 iam_s1_* 表。 */
@Mapper
public interface IamS1AccessRequestMapper extends BaseMapper<IamS1AccessRequest> {

    @Select("""
            SELECT id, protocol_version, requester_id, datasource_id, metadata_snapshot_id,
                   table_name, requested_columns_json, row_scope, requested_valid_until,
                   purpose, status, revision_no, created_by, updated_by, created_at, updated_at
            FROM iam_s1_access_request
            WHERE protocol_version = #{protocolVersion}
              AND requester_id = #{requesterId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<IamS1AccessRequest> selectByRequester(@Param("protocolVersion") String protocolVersion,
                                               @Param("requesterId") Long requesterId,
                                               @Param("limit") int limit);

    /**
     * 审批前锁定申请行，防止并发重复审批。
     */
    @Select("""
            SELECT id, protocol_version, requester_id, datasource_id, metadata_snapshot_id,
                   table_name, requested_columns_json, row_scope, requested_valid_until,
                   purpose, status, revision_no, created_by, updated_by, created_at, updated_at
            FROM iam_s1_access_request
            WHERE id = #{requestId}
            FOR UPDATE
            """)
    IamS1AccessRequest selectForUpdate(@Param("requestId") Long requestId);

    @Update("""
            UPDATE iam_s1_access_request
            SET status = #{status}, revision_no = #{revisionNo}, updated_by = #{operatorId}
            WHERE id = #{requestId}
              AND status = 'PENDING'
            """)
    int updateStatusIfPending(@Param("requestId") Long requestId,
                              @Param("status") String status,
                              @Param("revisionNo") Long revisionNo,
                              @Param("operatorId") Long operatorId);
}
