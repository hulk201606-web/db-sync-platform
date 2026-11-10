package com.dbsync.dialect;

import com.dbsync.common.exception.DriverMissingException;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 驱动反射加载（架构 §5.4）。
 *
 * <p>Oracle / DM / DB2 的驱动受授权限制，<b>不得</b>写进 {@code pom.xml}，
 * 必须由运维把 jar 放入 {@code <APP_HOME>/lib/} 并重启（Spring Boot {@code loader.path} 默认指向 lib/）。
 *
 * <p>加载后必须用 {@link DriverManager#getConnection} 而不是 {@code Driver#connect}：
 * 驱动会在静态块里自注册到 {@code DriverManager}。
 */
public final class JdbcDriverLoader {

    private static final Set<String> LOADED = ConcurrentHashMap.newKeySet();

    private JdbcDriverLoader() {
    }

    /**
     * 确保驱动已加载（幂等）。
     *
     * @param driverClassName 驱动类全名
     * @throws DriverMissingException {@code E7001} 驱动不在 classpath
     */
    public static void ensureLoaded(String driverClassName) {
        if (driverClassName == null || driverClassName.isBlank()) {
            throw new DriverMissingException("(未配置)", "数据源未配置 DbType 或驱动类名");
        }
        if (LOADED.contains(driverClassName)) {
            return;
        }
        try {
            Class.forName(driverClassName, true, Thread.currentThread().getContextClassLoader());
            LOADED.add(driverClassName);
        } catch (ClassNotFoundException e) {
            throw new DriverMissingException(driverClassName, buildMissingMessage(driverClassName));
        }
    }

    /**
     * 驱动是否可用（不抛异常）。
     *
     * @param driverClassName 驱动类全名
     * @return true 可用
     */
    public static boolean isAvailable(String driverClassName) {
        if (driverClassName == null || driverClassName.isBlank()) {
            return false;
        }
        if (LOADED.contains(driverClassName)) {
            return true;
        }
        try {
            Class.forName(driverClassName, true, Thread.currentThread().getContextClassLoader());
            LOADED.add(driverClassName);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * 当前 classpath 中已注册的驱动类名（用于错误消息里的"可用清单"）。
     *
     * @return 驱动类名列表
     */
    public static List<String> availableDrivers() {
        List<String> names = new ArrayList<>();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            names.add(drivers.nextElement().getClass().getName());
        }
        Collections.sort(names);
        return names;
    }

    /**
     * 构造缺失提示（架构 §5.4 模板：① 驱动类名 ② 三种加载方式 ③ 当前可用驱动清单）。
     *
     * @param driver 驱动类名
     * @return 完整提示
     */
    public static String buildMissingMessage(String driver) {
        return "该驱动未随包发布（授权限制）。请任选一种方式：\n"
                + "  1) 将对应 jar 放入 <APP_HOME>/lib/ 后重启（推荐，Spring Boot loader.path 已默认指向 lib/）\n"
                + "  2) java -Dloader.path=/opt/drivers -jar db-sync-server.jar\n"
                + "  3) 确认 pom 依赖中已加入对应坐标（仅 MySQL/PG/H2 可从公共仓库获取）\n"
                + "当前 classpath 中可用的 JDBC 驱动：" + String.join(", ", availableDrivers());
    }
}
