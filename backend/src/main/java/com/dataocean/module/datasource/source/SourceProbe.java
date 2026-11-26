package com.dataocean.module.datasource.source;

import com.dataocean.module.datasource.source.SourceConnection.Type;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 执行分项连通和权限测试，只返回经过整理的能力及覆盖信息。
 */
@Component
@RequiredArgsConstructor
public class SourceProbe {

    /**
     * 与正式采集共享目标、加密和元数据读取策略。
     */
    private final SourceJdbc jdbc;

    /**
     * 一项可披露的测试观察。
     *
     * @param code 稳定分项编号
     * @param label 分项名称
     * @param status PASS、FAIL、UNVERIFIED 或 NOT_APPLICABLE
     * @param message 可安全显示的结果原因
     */
    public record Item(String code, String label, String status, String message) {}

    /**
     * 本次测试的能力报告，不包含源库凭据、连接串或行数据。
     *
     * @param detectedVersion 源端识别的数据库版本
     * @param availableSchemas 数据库账号可见的 Schema
     * @param verifiedSchemas 本次测试实际验证的 Schema
     * @param structureObjectRefs 用于结构抽样的对象
     * @param items 七项测试
     * @param readOnlySafety 单独保存的账号只读安全结论
     * @param businessReadStatus 业务读取结论，未验证时保留 UNVERIFIED
     * @param canEnable 是否具备配置范围启用条件
     */
    public record Report(
            String detectedVersion,
            List<String> availableSchemas,
            List<String> verifiedSchemas,
            List<ObjectRef> structureObjectRefs,
            List<Item> items,
            String readOnlySafety,
            String businessReadStatus,
            boolean canEnable) {}

    /**
     * 依据源实际连接、目录和源端会话能力生成一次测试结果。
     */
    public Report check(SourceConnection source, String password) {
        List<Item> items = new ArrayList<>(List.of(
                new Item("CONFIGURATION", "配置有效性", "PASS", "必填配置已核对"),
                new Item("NETWORK", "网络与加密", "UNVERIFIED", "尚未验证"),
                new Item("AUTHENTICATION", "账户认证", "UNVERIFIED", "尚未验证"),
                new Item("ENUMERATION", "目录枚举", "UNVERIFIED", "尚未验证"),
                new Item("STRUCTURE", "结构读取", "UNVERIFIED", "尚未验证"),
                new Item("BUSINESS_READ", "业务只读能力", "UNVERIFIED", "尚未验证"),
                new Item("COMPATIBILITY", "限制与兼容性", "UNVERIFIED", "尚未验证")));
        String marker = "do-check-" + UUID.randomUUID();
        String version = null;
        String safety = "UNVERIFIED";
        List<String> available = List.of();
        List<String> verified = List.of();
        List<ObjectRef> coverage = new ArrayList<>();
        int stage = 1;

        try (Connection connection = jdbc.open(source, password, marker)) {
            update(items, 1, "PASS", "网络及服务端证书验证通过");
            update(items, 2, "PASS", "源库账号已通过认证");

            stage = 6;
            version = connection.getMetaData().getDatabaseProductVersion();
            jdbc.session(connection, source, marker);
            update(items, 6, "PASS", "适配器已确认源端连接观察能力");

            stage = 5;
            safety = jdbc.readOnlySafety(connection, source) ? "PASS" : "FAIL";
            update(items, 5, safety, "PASS".equals(safety) ? "只读安全已核对，业务行读取未验证" : "无法确认账号符合专用只读要求");
            if ("FAIL".equals(safety)) {
                // 不以具备写入能力的账号继续探测范围。
                return new Report(
                        version,
                        available,
                        verified,
                        List.copyOf(coverage),
                        List.copyOf(items),
                        safety,
                        "UNVERIFIED",
                        false);
            }

            stage = 3;
            available = jdbc.visibleSchemas(connection, source);
            List<String> selected = source.schemas().isEmpty() ? available : source.schemas();
            SourceConnection tested = new SourceConnection(
                    source.type(),
                    source.host(),
                    source.port(),
                    source.database(),
                    selected,
                    source.account(),
                    source.queryTimeoutSeconds());
            if (source.type() == Type.POSTGRESQL && selected.isEmpty()) {
                // 需要至少有一个可核验的当前库 Schema。
                throw new SQLException("当前数据库没有可用 Schema", "42501");
            }
            List<ObjectRef> rows = jdbc.enumerate(connection, tested);
            verified = List.copyOf(selected);
            update(items, 3, "PASS", "已枚举所测范围内的可见对象");

            stage = 4;
            for (ObjectRef object : rows.stream().limit(3).toList()) {
                // 分项测试仅抽查少量结构，完整判断由正式采集完成。
                jdbc.read(connection, tested, object);
                coverage.add(object);
            }
            update(
                    items,
                    4,
                    coverage.isEmpty() ? "NOT_APPLICABLE" : "PASS",
                    coverage.isEmpty() ? "当前测试范围内没有可见对象" : "已抽查 " + coverage.size() + " 个对象的结构");
        } catch (SQLException exception) {
            // 区分连接、密码和读取故障，禁止返回 JDBC 连接串或驱动原始错误。
            String category = failureCode(exception);
            if (stage == 1 && "SOURCE_AUTHENTICATION".equals(category)) {
                update(items, 1, "PASS", "已到达源库并完成加密握手");
                stage = 2;
            }
            String message = stage == 1 && "SOURCE_NETWORK".equals(category)
                    ? connectionFailureMessage(exception)
                    : failureMessage(category);
            update(items, stage, "FAIL", message);
            return new Report(
                    version,
                    available,
                    verified,
                    List.copyOf(coverage),
                    List.copyOf(items),
                    safety,
                    "UNVERIFIED",
                    false);
        }
        return new Report(
                version, available, verified, List.copyOf(coverage), List.copyOf(items), safety, "UNVERIFIED", true);
    }

    /**
     * 只按已知异常类型细分连接问题，避免把驱动原始错误或连接信息返回给操作人。
     */
    private String connectionFailureMessage(SQLException exception) {
        boolean tlsFailure = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            // DNS 无法解析时，连接与后续测试均未发生。
            if (cause instanceof UnknownHostException) {
                return "无法解析数据源主机名，请核对地址";
            }
            // 握手异常可能包着实际超时，先保留更具体的失败原因。
            if (cause instanceof SocketTimeoutException || cause instanceof SQLTimeoutException) {
                return "连接数据源超时，请核对网络和服务状态";
            }
            tlsFailure |= cause instanceof SSLException || cause instanceof CertificateException;
        }
        if (tlsFailure) {
            // 有明确握手或证书异常时，引导核对加密配置。
            return "数据源 TLS 握手或证书校验失败，请核对证书配置";
        }
        // 无明确类型时，只报告连接失败。
        return "无法连接数据源，请核对网络和服务状态";
    }

    /**
     * 按稳定 SQLState 分类，保留前端可以使用的失败原因。
     */
    public String failureCode(SQLException exception) {
        String state = exception.getSQLState();
        if (state == null) {
            return "SOURCE_STRUCTURE_INCOMPLETE";
        }
        if (state.startsWith("28")) {
            return "SOURCE_AUTHENTICATION";
        }
        if (state.startsWith("08") || state.startsWith("HYT") || state.equals("DO002")) {
            return "SOURCE_NETWORK";
        }
        return switch (state) {
            case "42501", "42000" -> "SOURCE_PERMISSION";
            case "DO001" -> "SOURCE_SCOPE_UNVERIFIED";
            case "DO004" -> "SOURCE_CAPABILITY_UNSUPPORTED";
            case "DO005" -> "SOURCE_STRUCTURE_UNSTABLE";
            default -> "SOURCE_STRUCTURE_INCOMPLETE";
        };
    }

    /**
     * 以稳定用户文案报告原因，源库的原始型号与权限信息仅按必要字段返回。
     */
    public String failureMessage(String code) {
        return switch (code) {
            case "SOURCE_AUTHENTICATION" -> "账号认证失败，请核对账号和密码";
            case "SOURCE_NETWORK" -> "网络连接、超时或证书校验失败，请检查源库连接";
            case "SOURCE_PERMISSION" -> "当前账号无法读取配置范围或所需结构";
            case "SOURCE_SCOPE_UNVERIFIED" -> "连接目标或读取范围未获配置策略允许";
            case "SOURCE_CAPABILITY_UNSUPPORTED" -> "源库无法提供必要的采集停止证据";
            case "SOURCE_STRUCTURE_UNSTABLE" -> "采集期间对象结构发生变化，请在结构稳定后重试";
            default -> "必要的结构信息读取不完整";
        };
    }

    /**
     * 按原型的固定顺序更新某一项测试结果。
     */
    private void update(List<Item> items, int index, String status, String message) {
        Item prior = items.get(index);
        items.set(index, new Item(prior.code(), prior.label(), status, message));
    }
}
