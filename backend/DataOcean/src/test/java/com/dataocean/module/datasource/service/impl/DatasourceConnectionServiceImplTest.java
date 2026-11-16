package com.dataocean.module.datasource.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dataocean.module.datasource.entity.po.Datasource;
import com.dataocean.module.datasource.entity.po.DatasourceHealthCheck;
import com.dataocean.module.datasource.mapper.DatasourceHealthCheckMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.SQLNonTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

class DatasourceConnectionServiceImplTest {

    @Test
    void failedConnectionLogsCorrelationWithoutConnectionDetailsOrDriverMessage() {
        DatasourceHealthCheckMapper healthCheckMapper = mock(DatasourceHealthCheckMapper.class);
        DatasourceConnectionServiceImpl service = new DatasourceConnectionServiceImpl(healthCheckMapper);
        Datasource datasource = new Datasource();
        datasource.setId(7L);
        datasource.setHost("private-db.example");
        datasource.setPort(3306);
        datasource.setDatabaseName("private_business");
        datasource.setCharset("utf8mb4");
        String driverMessage = "Connection failed for private-db.example/private_business user=private-user secret=private-password";
        SQLNonTransientConnectionException failure = new SQLNonTransientConnectionException(driverMessage);
        Logger logger = (Logger) LoggerFactory.getLogger(DatasourceConnectionServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                    .thenThrow(failure);

            var result = service.testConnection(datasource, "private-user", "private-password", DatasourceHealthCheck.TYPE_MANUAL);

            // 日志脱敏不改变现有接口错误及健康检查持久化合同。
            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getMessage()).isEqualTo(driverMessage);
            ArgumentCaptor<DatasourceHealthCheck> check = ArgumentCaptor.forClass(DatasourceHealthCheck.class);
            verify(healthCheckMapper).insert(check.capture());
            assertThat(check.getValue().getDatasourceId()).isEqualTo(7L);
            assertThat(check.getValue().getSuccess()).isZero();

            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(logs).contains("datasourceId=7", "checkType=MANUAL", "elapsedMs=", "errorType=SQLNonTransientConnectionException")
                    .doesNotContain("private-db.example", "private_business", "private-user", "private-password");
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
