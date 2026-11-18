package com.dataocean.module.iam.config;

import com.dataocean.common.TextSearch;
import com.dataocean.infrastructure.DataOceanProperties;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.iam.entity.dto.DepartmentProfileDTO;
import com.dataocean.module.iam.entity.dto.IdentitySeedsDTO;
import com.dataocean.module.iam.entity.dto.NewUserProfileDTO;
import com.dataocean.module.iam.entity.dto.RoleProfileDTO;
import com.dataocean.module.iam.entity.po.PermissionPO;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;
import com.dataocean.module.iam.mapper.CredentialMapper;
import com.dataocean.module.iam.mapper.DepartmentMapper;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.MembershipMapper;
import com.dataocean.module.iam.mapper.PermissionMapper;
import com.dataocean.module.iam.mapper.RoleAssignmentMapper;
import com.dataocean.module.iam.mapper.RoleMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.PasswordPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 根据显式环境配置初始化空新库，开发账号随 dev 配置提供，不开放匿名 HTTP 初始化入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityBootstrap implements ApplicationRunner {

    /**
     * 首管理员及根部门的受控配置引用。
     */
    private final DataOceanProperties properties;

    /**
     * 初始化与正式管理写共用的配置锁。
     */
    private final GuardMapper guard;

    /**
     * 首个人员及其凭据存储。
     */
    private final UserMapper users;

    /**
     * 保存账号凭据及其历史修订。
     */
    private final CredentialMapper credentials;

    /**
     * 根组织记录。
     */
    private final DepartmentMapper departments;

    /**
     * 主部门成员关系。
     */
    private final MembershipMapper memberships;

    /**
     * 版本化权限目录。
     */
    private final PermissionMapper permissions;

    /**
     * 预设角色及显式授予集合。
     */
    private final RoleMapper roles;

    /**
     * 初始化默认管理员的角色分配。
     */
    private final RoleAssignmentMapper roleAssignments;

    /**
     * 初始化业务审计。
     */
    private final AuditService audits;

    /**
     * 已确认的密码编码器。
     */
    private final PasswordEncoder encoder;

    /**
     * 种子和快照的类型化序列化。
     */
    private final JsonCodec json;

    /**
     * 支持仓库内开发资源和外部受控文件的统一读取。
     */
    private final ResourceLoader resources;

    /**
     * 整次初始化的原子事务。
     */
    private final TransactionTemplate transactions;

    /**
     * 只在配置齐全且库内无人时初始化，重复启动保持已有状态。
     *
     * @throws IOException 初始密码或功能目录不可读取时中止启动，不能回退为默认身份
     */
    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (properties.getBootstrapUsername() == null || users.exists(null)) {
            // 普通启动不重新读取密码文件，也不修改现有权限。
            return;
        }
        String reference = properties.getBootstrapPasswordFile();
        String password = reference.startsWith("classpath:") || reference.startsWith("file:")
                ? resources
                        .getResource(reference)
                        .getContentAsString(StandardCharsets.UTF_8)
                        .stripTrailing()
                : Files.readString(Path.of(reference), StandardCharsets.UTF_8);
        PasswordPolicy.requireStrong(password);
        String hash = encoder.encode(password);
        IdentitySeedsDTO seeds = json.read(
                new ClassPathResource("iam-seeds.json").getContentAsString(StandardCharsets.UTF_8),
                IdentitySeedsDTO.class);
        transactions.executeWithoutResult(status -> {
            // 竞争启动时锁后再次检查，避免初始化两份首管理员。
            if (guard.lock().isBootstrapCompleted() || users.exists(null)) {
                return;
            }
            NewUserProfileDTO admin = new NewUserProfileDTO();
            admin.setUsername(properties.getBootstrapUsername());
            admin.setDisplayName(properties.getBootstrapDisplayName());
            long userId = users.create(admin, null);
            credentials.create(userId, hash);
            DepartmentProfileDTO root = new DepartmentProfileDTO(
                    properties.getBootstrapDepartmentName(),
                    properties.getBootstrapDepartmentCode(),
                    null,
                    null,
                    null,
                    0);
            long rootId = departments.create(root, null, null, userId);
            memberships.add(userId, rootId, true, userId);
            int order = 0;
            for (var page : seeds.getPages()) {
                // 页面权与动作的显式归属来自版本化目录，不以名称推断权限。
                for (var operation : page.getOperations()) {
                    boolean isPage = operation.getId().equals(page.getId() + ".view");
                    permissions.insert(new PermissionPO(
                            operation.getId(),
                            isPage ? null : page.getId() + ".view",
                            page.getId(),
                            isPage ? page.getName() : operation.getName(),
                            operation.getPurpose(),
                            isPage ? "PAGE" : "ACTION",
                            operation.isProtectedAbility(),
                            true,
                            order++));
                }
            }
            List<String> ordinaryGrant = new ArrayList<>();
            seeds.getRoles().stream().skip(1).forEach(role -> ordinaryGrant.addAll(role.getPermissions()));
            long superRole = 0;
            for (int index = 0; index < seeds.getRoles().size(); index++) {
                // 四个模板只初始化功能，不创建任何业务源访问。
                var seed = seeds.getRoles().get(index);
                long roleId = roles.create(
                        new RoleProfileDTO(seed.getName(), seed.getDescription(), seed.getPermissions()),
                        seed.getId(),
                        TextSearch.key(seed.getName()),
                        true,
                        userId);
                seed.getPermissions().forEach(code -> roles.addPermission(roleId, code, userId));
                List<String> grants = index == 0
                        ? permissions.all().stream().map(PermissionPO::getCode).toList()
                        : index == 1
                                ? ordinaryGrant.stream()
                                        .distinct()
                                        .filter(code -> permissions.all().stream()
                                                .noneMatch(p -> p.getCode().equals(code) && p.isProtected()))
                                        .toList()
                                : List.of();
                grants.forEach(code -> roles.addGrant(roleId, code, userId));
                roles.appendSnapshot(roleId, json.write(seed.getPermissions()), json.write(grants), userId);
                if (index == 0) {
                    superRole = roleId;
                }
            }
            roleAssignments.assign(userId, superRole, 1, userId);
            audits.bootstrap(AuditWriteDTO.builder()
                    .actionCode("iam.bootstrap")
                    .targetType("user")
                    .targetId(Long.toString(userId))
                    .targetName(admin.getDisplayName())
                    .changes(List.of(new ChangeVO("identity", "首管理员", null, admin.getUsername())))
                    .beforeRoles(List.of())
                    .afterRoles(List.of(new RoleVersionRefVO(Long.toString(superRole), 1)))
                    .securityVersion(guard.selectById(1).getSecurityVersion())
                    .build());
        });
        log.info("空库身份初始化完成，需首次改密后建立有效恢复途径");
    }
}
