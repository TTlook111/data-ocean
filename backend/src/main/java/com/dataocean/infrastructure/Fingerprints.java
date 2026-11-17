package com.dataocean.infrastructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

/**
 * 为已绑定的明确 DTO 生成规范指纹，不接收外部任意 JSON 业务写入。
 */
@Component
@RequiredArgsConstructor
public class Fingerprints {

    /**
     * 核对摘要中排除的版本控制与秘密字段，秘密仍参与受控命令指纹。
     */
    private static final Set<String> REVIEW_OMISSIONS = Set.of(
            "expectedVersion",
            "reviewToken",
            "reset",
            "password",
            "currentPassword",
            "initialPassword",
            "newPassword",
            "confirmPassword");

    /**
     * 统一的明确类型 JSON 编解码适配。
     */
    private final JsonCodec json;

    /**
     * 受控密钥读取、HMAC 及恒定时间比较能力。
     */
    private final SecretKeyProvider secrets;

    /**
     * 按类型化对象生成稳定规范内容，用于核对和防重。
     */
    public String canonical(Object value, boolean review) {
        return canonicalNode(json.mapper().valueToTree(value), review);
    }

    /**
     * 对当前动作、目标和完整请求生成带密钥内容指纹。
     */
    public byte[] command(String operation, String target, Object value) {
        return secrets.hmac(operation + "|" + target + "|" + canonical(value, false));
    }

    /**
     * 为非秘密完整提案生成稳定受控摘要，供预览和正式提交核对。
     */
    public String proposal(Object value) {
        return HexFormat.of().formatHex(secrets.hmac(canonical(value, true)));
    }

    /**
     * 按稳定字段顺序规范化已绑定节点，按用途排除控制或秘密字段。
     */
    private String canonicalNode(JsonNode node, boolean review) {
        if (node.isObject()) {
            var fields = new ArrayList<String>();
            node.properties().forEach(entry -> {
                if (!entry.getKey().equals("reviewToken") && (!review || !REVIEW_OMISSIONS.contains(entry.getKey()))) {
                    fields.add(json.write(entry.getKey()) + ":" + canonicalNode(entry.getValue(), review));
                }
            });
            fields.sort(Comparator.naturalOrder());
            return "{" + String.join(",", fields) + "}";
        }
        if (node.isArray()) {
            var values = new ArrayList<String>();
            node.forEach(item -> values.add(canonicalNode(item, review)));
            return "[" + String.join(",", values) + "]";
        }
        return node.toString();
    }
}
