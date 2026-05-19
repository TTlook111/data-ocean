package com.dataocean.module.datasource.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourceSecretServiceImplTest {

    @Test
    void encryptAndDecryptShouldRoundTripWithoutPlaintextStorage() {
        DatasourceSecretServiceImpl service = new DatasourceSecretServiceImpl("12345678901234567890123456789012");

        String encrypted = service.encrypt("plain-password");

        assertThat(encrypted).isNotEqualTo("plain-password");
        assertThat(service.decrypt(encrypted)).isEqualTo("plain-password");
    }
}
