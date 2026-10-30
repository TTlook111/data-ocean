package com.dataocean.module.permission.s1.bootstrap;

import org.springframework.core.env.Environment;

/** IAM-SIMPLE-1 bootstrap 模式的统一判定入口。 */
public final class IamS1BootstrapMode {

    public static final String ENABLED_ENV_KEY = "IAM_S1_BOOTSTRAP_ENABLED";
    public static final String USER_ID_ENV_KEY = "IAM_S1_BOOTSTRAP_USER_ID";
    public static final String ENABLED_PROPERTY_KEY = "iam.s1.bootstrap.enabled";
    public static final String USER_ID_PROPERTY_KEY = "iam.s1.bootstrap.user-id";

    private IamS1BootstrapMode() {
    }

    public static boolean isEnabled(String[] args) {
        if (isTrue(commandLineValue(args, ENABLED_ENV_KEY))
                || isTrue(commandLineValue(args, ENABLED_PROPERTY_KEY))) {
            return true;
        }
        if (isTrue(System.getProperty(ENABLED_ENV_KEY))
                || isTrue(System.getProperty(ENABLED_PROPERTY_KEY))) {
            return true;
        }
        return isTrue(System.getenv(ENABLED_ENV_KEY));
    }

    public static boolean isEnabled(Environment environment) {
        return isTrue(firstNonBlank(
                environment.getProperty(ENABLED_ENV_KEY),
                environment.getProperty(ENABLED_PROPERTY_KEY)));
    }

    public static String targetUserId(Environment environment) {
        return firstNonBlank(
                environment.getProperty(USER_ID_ENV_KEY),
                environment.getProperty(USER_ID_PROPERTY_KEY));
    }

    private static String commandLineValue(String[] args, String key) {
        if (args == null) {
            return null;
        }
        String prefix = "--" + key;
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix + "=")) {
                return arg.substring(prefix.length() + 1);
            }
        }
        return null;
    }

    private static boolean isTrue(String value) {
        return "true".equalsIgnoreCase(value);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
