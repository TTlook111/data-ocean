package com.dataocean.module.user.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1DataGrant;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.dto.DepartmentCreateDTO;
import com.dataocean.module.user.entity.dto.DepartmentUpdateDTO;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 部门创建、改父级、启停、删除必须写入 S1 revision；删除时撤回部门 grant。
 */
@ExtendWith(MockitoExtension.class)
class DepartmentServiceImplS1RevisionTest {

    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private IamS1DataGrantMapper iamS1DataGrantMapper;
    @Mock
    private IamS1PermissionRevisionService revisionService;
    @Mock
    private IamS1AuditEventService auditEventService;
    @InjectMocks
    private DepartmentServiceImpl service;

    @Test
    void createDepartmentRecordsRevision() {
        DepartmentCreateDTO request = new DepartmentCreateDTO();
        request.setDeptName("销售");
        request.setDeptCode("SALES");
        when(departmentMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            SysDepartment department = invocation.getArgument(0);
            department.setId(15L);
            return 1;
        }).when(departmentMapper).insert(any(SysDepartment.class));
        when(revisionService.record(eq("DEPARTMENT"), eq(15L), eq("CREATE"), any(), any())).thenReturn(4L);

        Long id = service.createDepartment(request);

        assertThat(id).isEqualTo(15L);
        verify(revisionService).record("DEPARTMENT", 15L, "CREATE", null, "创建部门");
        verify(auditEventService).recordSuccess(eq("DEPARTMENT_CREATE"), any(), eq("DEPARTMENT"), eq(15L),
                any(), any(), any(), any());
    }

    @Test
    void updateDepartmentRecordsParentChangeRevision() {
        SysDepartment department = department(8L, null, "销售", 1);
        when(departmentMapper.selectById(8L)).thenReturn(department);
        SysDepartment parent = department(3L, null, "总部", 1);
        when(departmentMapper.selectById(3L)).thenReturn(parent);
        when(departmentMapper.selectCount(any())).thenReturn(0L);
        when(revisionService.record(eq("DEPARTMENT"), eq(8L), eq("PARENT_CHANGE"), any(), any())).thenReturn(9L);

        DepartmentUpdateDTO request = new DepartmentUpdateDTO();
        request.setParentId(3L);
        request.setDeptName("销售");
        request.setDeptCode("SALES");
        request.setStatus(1);
        service.updateDepartment(8L, request);

        verify(revisionService).record("DEPARTMENT", 8L, "PARENT_CHANGE", null, "更新部门");
    }

    @Test
    void updateDepartmentRecordsDisableRevision() {
        SysDepartment department = department(8L, null, "销售", 1);
        when(departmentMapper.selectById(8L)).thenReturn(department);
        when(departmentMapper.selectCount(any())).thenReturn(0L);
        when(revisionService.record(eq("DEPARTMENT"), eq(8L), eq("DISABLE"), any(), any())).thenReturn(10L);

        DepartmentUpdateDTO request = new DepartmentUpdateDTO();
        request.setDeptName("销售");
        request.setDeptCode("SALES");
        request.setStatus(0);
        service.updateDepartment(8L, request);

        verify(revisionService).record("DEPARTMENT", 8L, "DISABLE", null, "更新部门");
    }

    @Test
    void deleteDepartmentRevokesGrantsRecordsRevisionThenDeletes() {
        SysDepartment department = department(8L, null, "销售", 1);
        when(departmentMapper.selectById(8L)).thenReturn(department);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(departmentMapper.selectCount(any())).thenReturn(0L);
        IamS1DataGrant grant = new IamS1DataGrant();
        grant.setId(21L);
        grant.setStatus(IamS1Constants.DATA_GRANT_STATUS_ACTIVE);
        when(iamS1DataGrantMapper.selectActiveBySubjectForUpdate(
                IamS1Constants.PROTOCOL_VERSION, IamS1Constants.SUBJECT_DEPARTMENT, 8L))
                .thenReturn(List.of(grant));
        when(revisionService.record(eq("DEPARTMENT"), eq(8L), eq("DELETE"), any(), any())).thenReturn(11L);

        service.deleteDepartment(8L);

        assertThat(grant.getStatus()).isEqualTo(IamS1Constants.DATA_GRANT_STATUS_REVOKED);
        assertThat(grant.getRevisionNo()).isEqualTo(11L);
        verify(iamS1DataGrantMapper).updateById(grant);
        verify(departmentMapper).deleteById(8L);
        verify(revisionService).record("DEPARTMENT", 8L, "DELETE", null, "删除部门");
    }

    @Test
    void deleteDepartmentDoesNotRevokeWhenDepartmentIsNotEmpty() {
        SysDepartment department = department(8L, null, "销售", 1);
        when(departmentMapper.selectById(8L)).thenReturn(department);
        when(userMapper.selectCount(any())).thenReturn(2L);
        when(departmentMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.deleteDepartment(8L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非空部门");

        verify(departmentMapper, never()).deleteById(8L);
        verify(revisionService, never()).record(any(), any(), any(), any(), any());
        verify(iamS1DataGrantMapper, never()).selectActiveBySubjectForUpdate(any(), any(), any());
    }

    private static SysDepartment department(Long id, Long parentId, String name, int status) {
        SysDepartment department = new SysDepartment();
        department.setId(id);
        department.setParentId(parentId);
        department.setDeptName(name);
        department.setDeptCode("SALES");
        department.setStatus(status);
        return department;
    }
}
