package com.dataocean.module.datasource.service;

import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestResult;

public interface DatasourceConnectionService {

    DatasourceConnectionTestResult testConnection(String host,
                                                  Integer port,
                                                  String databaseName,
                                                  String charset,
                                                  String username,
                                                  String password);

    DatasourceConnectionTestResult testConnection(Datasource datasource, String username, String password, String checkType);
}
