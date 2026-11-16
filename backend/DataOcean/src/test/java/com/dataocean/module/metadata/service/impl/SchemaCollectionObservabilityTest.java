package com.dataocean.module.metadata.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.metadata.entity.po.SchemaSyncTask;
import com.dataocean.module.metadata.service.SchemaSyncTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaCollectionObservabilityTest {

    @Mock private DatasourceMapper datasourceMapper;
    @Mock private SchemaSyncTaskService syncTaskService;
    @InjectMocks private SchemaCollectionServiceImpl service;

    @Test
    void failedCollectionLogsTaskIdentityWithoutDatabaseExceptionDetails() {
        SchemaSyncTask task = new SchemaSyncTask();
        task.setId(19L);
        String driverMessage = "jdbc:mysql://private-db/private_business password=private-password";
        when(datasourceMapper.selectById(7L))
                .thenThrow(new IllegalStateException(driverMessage, new SQLException(driverMessage)));
        Logger logger = (Logger) LoggerFactory.getLogger(SchemaCollectionServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.doSyncAsync(7L, task, false);

            // 日志精简后仍须保持原来的失败状态传播，不能误报 SUCCESS。
            var order = inOrder(syncTaskService);
            order.verify(syncTaskService).updateStatus(19L, SchemaSyncTask.STATUS_RUNNING, null);
            order.verify(syncTaskService).updateStatus(19L, SchemaSyncTask.STATUS_FAILED, driverMessage);
            order.verifyNoMoreInteractions();
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(logs).contains("datasourceId=7", "taskId=19", "elapsedMs=", "errorType=IllegalStateException", "causeType=SQLException")
                    .doesNotContain("private-db", "private_business", "private-password", "采集任务完成");
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
