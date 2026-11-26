package com.dataocean.module.datasource.service;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.exceptions.OperationException;
import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.module.datasource.config.NoteOssProperties;
import com.dataocean.module.datasource.entity.po.NoteAttachmentPO;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 按平台库记录的定位读取私有原件，统一处理 OSS 客户端与错误边界。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NoteAttachmentStorage {

    /**
     * 本机或部署环境的 OSS 访问配置和对象前缀。
     */
    private final NoteOssProperties properties;

    /**
     * 延迟获取客户端，使纯正文和其他功能无需连接 OSS 即可运行。
     */
    private final ObjectProvider<OSSClient> clients;

    /**
     * 返回具备必要连接配置的 Bucket，供后台清理限定范围。
     */
    String configuredBucket() {
        if (!StringUtils.hasText(properties.getBucket())
                || !StringUtils.hasText(properties.getRegion())
                || !StringUtils.hasText(properties.getAccessKeyId())
                || !StringUtils.hasText(properties.getAccessKeySecret())) {
            // 配置尚未提供时，已有待清理任务保留在平台库等待后续处理。
            return null;
        }
        return properties.getBucket();
    }

    /**
     * 给新增原件分配与原文件名无关且不会覆盖旧版的对象键。
     */
    public void assignLocation(NoteAttachmentPO file) {
        var bucket = configuredBucket();
        if (bucket == null) {
            // 仅涉及附件时才提示开发人员配置 OSS。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_UNAVAILABLE, "请先配置附件 OSS");
        }
        var prefix = properties.getObjectPrefix();
        if (prefix == null || !prefix.matches("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,239}")) {
            // 对象前缀使用可安全落库和跨环境部署的 ASCII 路径字符。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_UNAVAILABLE, "附件 OSS 对象前缀配置无效");
        }
        file.setObjectBucket(bucket);
        file.setObjectKey(prefix.replaceFirst("/+$", "") + "/business-notes/" + UUID.randomUUID());
    }

    /**
     * 写入经过本地校验的完整字节，调用方在上传前已锁定清理预留。
     */
    public void upload(NoteAttachmentPO file, byte[] bytes) {
        try {
            // 上传使用生成的对象键，实际文件名只用于平台受控下载。
            client(file.getObjectBucket())
                    .putObject(PutObjectRequest.newBuilder()
                            .bucket(file.getObjectBucket())
                            .key(file.getObjectKey())
                            .body(BinaryData.fromBytes(bytes))
                            .build());
        } catch (OperationException | ServiceException failure) {
            // 对外返回统一的依赖错误，保留可定位的受控诊断信息。
            throw unavailable("上传", file.getId(), failure);
        }
    }

    /**
     * 读取已核权的附件，按保存时的大小限制读取量并关闭响应流。
     */
    public byte[] download(NoteAttachmentPO file) {
        var request = GetObjectRequest.newBuilder()
                .bucket(file.getObjectBucket())
                .key(file.getObjectKey())
                .build();
        try (var body = client(file.getObjectBucket()).getObject(request).body()) {
            // 多读一个字节以区分准确大小和意外超限的内容。
            byte[] bytes = body.readNBytes(file.getByteSize() + 1);
            if (bytes.length != file.getByteSize() || !sha256(bytes).equals(file.getDigest())) {
                // 固定修订的文件大小和摘要均一致时才交付原件。
                throw new IOException("附件 OSS 响应的大小或摘要与记录不一致");
            }
            return bytes;
        } catch (IOException | OperationException | ServiceException failure) {
            // 没有 OSS 响应或对象异常时，不能当作空附件使用。
            throw unavailable("下载", file.getId(), failure);
        }
    }

    /**
     * 计算完整原件摘要，用于命令防重和下载完整性校验。
     */
    public static String sha256(byte[] bytes) {
        try {
            // 对字节原样计算，原件在不同修订间可用同一摘要比较。
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            // Java 标准实现必须提供 SHA-256。
            throw new IllegalStateException("运行环境缺少 SHA-256", failure);
        }
    }

    /**
     * 删除清理任务绑定的对象，重复处理同一对象时沿用 OSS 删除语义。
     */
    void delete(String bucket, String objectKey) {
        client(bucket)
                .deleteObject(DeleteObjectRequest.newBuilder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build());
    }

    /**
     * 只用当前环境的配置读取或清理其对应 Bucket 的原件。
     */
    private OSSClient client(String bucket) {
        if (bucket == null || !bucket.equals(configuredBucket())) {
            // 部署误配到另一个 Bucket 时保留原定位，等待恢复正确配置。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_UNAVAILABLE, "附件 OSS 存储配置不可用");
        }
        var endpoint = properties.getEndpoint();
        if (StringUtils.hasText(endpoint) ? !endpoint.startsWith("https://") : properties.isUseCname()) {
            // SDK 使用 HTTPS；启用自定义域名时必须明确提供其地址。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_UNAVAILABLE, "附件 OSS HTTPS 域名配置无效");
        }
        try {
            // 延迟构建失败只影响附件依赖，不阻断其他功能。
            return clients.getObject();
        } catch (BeansException failure) {
            // SDK 初始化错误可能含内部域名，仅记录受控异常类型。
            throw unavailable("初始化", null, failure);
        }
    }

    /**
     * 记录受控对象标识和错误类型，避免输出签名请求及凭据。
     */
    private BizIllegalException unavailable(String operation, Long fileId, Exception failure) {
        var serviceError = ServiceException.asCause(failure);
        log.warn(
                "附件 OSS 操作失败，operation={}, fileId={}, errorType={}, status={}",
                operation,
                fileId,
                failure.getClass().getSimpleName(),
                serviceError == null ? null : serviceError.statusCode());
        return new BizIllegalException(ErrorCode.DEPENDENCY_UNAVAILABLE, "附件存储暂不可用，请稍后重试", failure);
    }
}
