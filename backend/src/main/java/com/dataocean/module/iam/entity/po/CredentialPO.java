package com.dataocean.module.iam.entity.po;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.ToString;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude={"passwordHash"})
public final class CredentialPO {
    private Long userId;
    private String passwordHash;
    private int credentialRevision;
    private LocalDateTime changedAt;
}
