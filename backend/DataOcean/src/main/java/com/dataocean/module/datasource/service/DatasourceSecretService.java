package com.dataocean.module.datasource.service;

public interface DatasourceSecretService {

    String encrypt(String plainPassword);

    String decrypt(String encryptedPassword);
}
