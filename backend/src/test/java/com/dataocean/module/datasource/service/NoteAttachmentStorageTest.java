package com.dataocean.module.datasource.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.exceptions.OperationException;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.module.datasource.config.NoteOssProperties;
import com.dataocean.module.datasource.entity.po.NoteAttachmentPO;
import com.dataocean.module.datasource.mapper.NoteAttachmentCleanupMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * 使用替身客户端验证原件完整性、存储范围和失败删除重试。
 */
class NoteAttachmentStorageTest {

    /**
     * 确认写入安全对象键及精确字节，下载时拒绝大小相同但内容不同的对象。
     */
    @Test
    void storesAndReadsExactBytes() {
        var client = mock(OSSClient.class);
        var storage = storage(client);
        byte[] bytes = "original".getBytes(StandardCharsets.UTF_8);
        var file = new NoteAttachmentPO();
        file.setName("原件.txt");
        file.setByteSize(bytes.length);
        file.setDigest(NoteAttachmentStorage.sha256(bytes));
        storage.assignLocation(file);
        storage.upload(file, bytes);

        var put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(put.capture());
        assertThat(put.getValue().bucket()).isEqualTo("attachment-example");
        assertThat(put.getValue().key()).isEqualTo(file.getObjectKey()).startsWith("dev/member/business-notes/");
        assertThat(put.getValue().body().toBytes()).isEqualTo(bytes);

        // 第一次读取原件，第二次读取等长但已变化的原件。
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(
                        GetObjectResult.newBuilder()
                                .innerBody(new ByteArrayInputStream(bytes))
                                .build(),
                        GetObjectResult.newBuilder()
                                .innerBody(new ByteArrayInputStream(new byte[bytes.length]))
                                .build());
        assertThat(storage.download(file)).isEqualTo(bytes);
        assertThatThrownBy(() -> storage.download(file))
                .isInstanceOf(BizIllegalException.class)
                .hasMessage("附件存储暂不可用，请稍后重试");
    }

    /**
     * 当前部署的凭据不能用于读取数据库里绑定了其他 Bucket 的原件。
     */
    @Test
    void rejectsAttachmentFromAnotherBucket() {
        var client = mock(OSSClient.class);
        var storage = storage(client);
        var file = new NoteAttachmentPO();
        file.setObjectBucket("another-bucket");
        file.setObjectKey("dev/other/business-notes/original");

        assertThatThrownBy(() -> storage.download(file))
                .isInstanceOf(BizIllegalException.class)
                .hasMessage("附件 OSS 存储配置不可用");
        verifyNoInteractions(client);
    }

    /**
     * 远端删除失败时更新下一次尝试时间，成功后才确认任务。
     */
    @Test
    void retainsCleanupUntilObjectDeletionSucceeds() {
        var client = mock(OSSClient.class);
        var tasks = mock(NoteAttachmentCleanupMapper.class);
        var cleanup = new NoteAttachmentCleanup(tasks, storage(client));
        var key = "dev/member/business-notes/original";
        when(tasks.next("attachment-example")).thenReturn(key);
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(new OperationException("DeleteObject"))
                .thenReturn(null);

        cleanup.cleanNext();
        cleanup.cleanNext();

        var order = inOrder(tasks, client);
        order.verify(tasks).next("attachment-example");
        order.verify(client).deleteObject(any(DeleteObjectRequest.class));
        order.verify(tasks).retry("attachment-example", key);
        order.verify(tasks).next("attachment-example");
        order.verify(client).deleteObject(any(DeleteObjectRequest.class));
        order.verify(tasks).complete("attachment-example", key);
        order.verifyNoMoreInteractions();
    }

    /**
     * 创建仅使用合成配置和内存客户端的附件存储。
     */
    private NoteAttachmentStorage storage(OSSClient client) {
        var properties = new NoteOssProperties();
        properties.setRegion("cn-hangzhou");
        properties.setBucket("attachment-example");
        properties.setObjectPrefix("dev/member");
        properties.setAccessKeyId("sample-key-id");
        properties.setAccessKeySecret("sample-key-secret");

        var clients = new StaticListableBeanFactory();
        clients.addBean("ossClient", client);
        return new NoteAttachmentStorage(properties, clients.getBeanProvider(OSSClient.class));
    }
}
