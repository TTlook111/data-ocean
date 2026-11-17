package com.dataocean.common.security;

import java.io.Serializable;
import java.security.Principal;

public record IamPrincipal(long userId, long authenticationVersion, String username, String displayName)
        implements Principal, Serializable {
    @Override public String getName() { return Long.toString(userId); }
}
