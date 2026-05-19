package com.dataocean.module.datasource.service;

import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestVO;

public interface DatasourceConnectionService {

    DatasourceConnectionTestVO testConnection(String host,
                                                  Integer port,
                                                  String databaseName,
                                                  String charset,
                                                  String username,
                                                  String password);

    DatasourceConnectionTestVO testConnection(Datasource datasource, String username, String password, String checkType);
}
