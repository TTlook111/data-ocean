package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.datasource.entity.po.CredentialChangePO;
import com.dataocean.module.datasource.entity.po.DatasourceCredentialPO;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 保存来源凭据替换操作及其真实停止依赖。
 */
@Repository
@RequiredArgsConstructor
public class CredentialChangeMapper {

    /**
     * 按来源查找实际凭据替换及候选密文。
     */
    private final CredentialChangeRecordMapper changes;

    /**
     * 读取绑定到该来源的候选凭据。
     */
    private final DatasourceCredentialMapper credentials;

    /**
     * 按来源范围读取一次实际替换。
     */
    public CredentialChangePO change(long source, String id) {
        return changes.selectOne(Wrappers.<CredentialChangePO>lambdaQuery()
                .eq(CredentialChangePO::getSourceId, source)
                .eq(CredentialChangePO::getId, id));
    }

    /**
     * 以来源、操作者和命令共同定位原异步替换。
     */
    public CredentialChangePO byCommand(long source, long actor, String command) {
        return changes.selectOne(Wrappers.<CredentialChangePO>lambdaQuery()
                .eq(CredentialChangePO::getSourceId, source)
                .eq(CredentialChangePO::getActorId, actor)
                .eq(CredentialChangePO::getCommandId, command));
    }

    /**
     * 寻找不再由在途任务占用来源的替换操作。
     */
    public List<CredentialChangePO> ready() {
        return changes.ready();
    }

    /**
     * 只读取由本源替换命令固定的候选密文。
     */
    public DatasourceMapper.CredentialRow secret(CredentialChangePO change) {
        var row = credentials.selectOne(Wrappers.<DatasourceCredentialPO>lambdaQuery()
                .eq(DatasourceCredentialPO::getSourceId, change.getSourceId())
                .eq(DatasourceCredentialPO::getId, change.getCandidateCredentialRevisionId())
                .select(DatasourceCredentialPO::getKeyVersion, DatasourceCredentialPO::getCiphertext));
        return Optional.ofNullable(row)
                .map(value -> new DatasourceMapper.CredentialRow(value.getKeyVersion(), value.getCiphertext()))
                .orElseThrow();
    }
}
