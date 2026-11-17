import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * 验收工具共用本机配置读取和新平台库只读身份核验，不输出秘密配置。
 */
final class VerificationEnvironment {

    /**
     * 工具类不提供业务实例。
     */
    private VerificationEnvironment() {
    }

    /**
     * 读取配置前关闭诊断输出，避免 YAML 调试器记录受控文件中的秘密值。
     */
    static Properties read(Path path) {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);

        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(path));
        return yaml.getObject();
    }

    /**
     * 只接受本机 dataocean 新模型，旧库或其他项目不会进入故障注入。
     */
    static void verify(Connection connection, String url) throws SQLException {
        if (!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/dataocean(?:\\?.*)?")
                || !connection.getCatalog().equals("dataocean") || connection.getMetaData().getDatabaseMajorVersion() != 8) {
            throw new IllegalStateException("数据库实例或库名与本机新平台目标不符");
        }
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('iam_security_guard','sys_command','sys_outbox','audit_record','iam_user_credential')")) {
            // 表名标记用于拒绝旧系统目标，实际故障仅作用于验收脚本自己的合成命令。
            result.next();
            if (result.getInt(1) != 5) {
                throw new IllegalStateException("目标不是本批新平台业务模型");
            }
        }
    }
}
