package com.dataocean.module.iam.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.iam.entity.dto.AuthenticationInvalidatedDTO;
import com.dataocean.module.iam.entity.dto.AuthorizationChangedDTO;
import com.dataocean.module.iam.entity.dto.SubjectRefDTO;
import com.dataocean.module.iam.entity.po.OutboxPO;
import com.dataocean.module.iam.entity.vo.CommandStatusVO;
import com.dataocean.module.iam.entity.vo.EntityVersionRefVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.CommandMapper;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.OutboxMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MutationServiceImpl implements MutationService {
    private final GuardMapper guard;
    private final UserMapper users;
    private final CommandMapper commands;
    private final OutboxMapper outbox;
    private final PermissionService permissions;
    private final AuditService audits;
    private final JsonCodec json;
    private final Fingerprints fingerprints;
    private final SecretKeyProvider secrets;
    @Transactional
    public MutationVO execute(String operation,String command,String target,Object payload,Integer expectedVersion,boolean authorizationChange,boolean allowInitialPassword,Supplier<MutationVO> action) {
        // 配置写使用排他锁，普通资料写使用共享放行锁；均在锁后重新核对可信身份和能力。
        if(authorizationChange)guard.lock();else guard.shared();
        var principal=UserContext.current();
        if(!authorizationChange) {
            // 普通资料编辑按用户编号顺序锁操作者与目标，避免相互编辑时的外键审计死锁。
            var locked=new java.util.TreeSet<Long>();locked.add(principal.userId());
            if(target.matches("[1-9][0-9]{0,18}"))locked.add(Ids.parse(target));
            locked.forEach(users::lock);
        }
        var actor=users.find(principal.userId());
        if(actor==null || !actor.getLifecycle().equals("ENABLED") || actor.getAuthenticationVersion()!=principal.authenticationVersion()) throw new BizIllegalException(ErrorCode.AUTH_EXPIRED,"登录已失效，请重新登录");
        if(actor.isMustChangePassword() && !allowInitialPassword) throw new BizIllegalException(ErrorCode.PASSWORD_CHANGE_REQUIRED,"请先完成初始改密");
        if(!Set.of("account.contact","account.password","auth.password.initial").contains(operation)) permissions.require(operation);
        String key=normalizeCommand(command);
        byte[] fingerprint=fingerprints.command(operation,target,payload);
        var existing=commands.find(Long.toString(principal.userId()),operation,key);
        if(existing!=null) {
            if(!secrets.matches(existing.getPayloadHmac(),fingerprint))throw new BizIllegalException(ErrorCode.COMMAND_CONTENT_CONFLICT,"同一操作编号的内容不同，请核对原操作");
            if(!existing.getState().equals("COMMITTED"))throw new BizIllegalException(ErrorCode.COMMAND_IN_PROGRESS,"原操作仍在处理中，请查询其状态");
            return json.read(existing.getResultRef(),MutationVO.class);
        }
        commands.insert(principal.userId(),operation,key,fingerprint);
        MutationVO result=action.get();result.setCommandId(key);
        boolean changed=expectedVersion==null || result.getVersion()!=expectedVersion;
        if(changed) {
            permissions.ensureRecovery();
            if(authorizationChange) {
                guard.increment();
                var payloadEvent=new AuthorizationChangedDTO(Long.toString(guard.current().getSecurityVersion()),operation,List.of(new SubjectRefDTO(result.getTargetType().toUpperCase(java.util.Locale.ROOT),result.getTargetId())),List.of(),"ALL_OF_SUBJECTS");
                enqueue("iam.authorization.changed",result.getTargetType(),result.getTargetId(),result.getVersion(),key,payloadEvent);
            }
            audits.assertRecorded(principal.userId(),key,operation,result.getTargetId());
        }
        commands.complete(principal.userId(),operation,key,json.write(result));
        return result;
    }
    public long auditSecurityVersion(boolean authorizationChange) { return guard.current().getSecurityVersion()+(authorizationChange?1:0); }
    public void invalidateAuthentication(long id,String reason) {
        var user=users.find(id);
        enqueue("iam.authentication.invalidated","user",Long.toString(id),user.getVersion(),null,new AuthenticationInvalidatedDTO(Long.toString(id),user.getAuthenticationVersion(),reason));
    }
    private void enqueue(String type,String aggregate,String id,int version,String command,Object payload) {
        outbox.insert(new OutboxPO(UUID.randomUUID().toString(),type,1,aggregate,id,version,command,json.write(payload),"PENDING",0,null,null,null,null,null,null));
    }
    public CommandStatusVO status(String command,String operation) {
        var found=commands.find(Long.toString(UserContext.getUser()),operation,normalizeCommand(command));
        if(found==null)throw BizIllegalException.missing();
        if(!Set.of("account.contact","account.password","auth.password.initial").contains(operation)) permissions.require(operation);
        MutationVO result=found.getResultRef()==null?null:json.read(found.getResultRef(),MutationVO.class);
        return new CommandStatusVO(found.getCommandId(),found.getOperationCode(),found.getState(),found.getResultCode(),result==null?null:new EntityVersionRefVO(result.getTargetId(),result.getVersion()));
    }
    private String normalizeCommand(String value) {
        if(value==null)throw BizIllegalException.invalid("缺少操作编号");
        try {String key=UUID.fromString(value).toString();if(!key.equals(value.toLowerCase(java.util.Locale.ROOT)))throw new IllegalArgumentException();return key;}
        catch(IllegalArgumentException error){throw BizIllegalException.invalid("操作编号须为 UUID");}
    }
}
