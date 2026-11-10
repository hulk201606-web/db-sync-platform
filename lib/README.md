# lib/ —— 受授权限制的 JDBC 驱动放置目录

Oracle / 达梦 DM / DB2 的驱动**不随本工程打包**（许可限制），需要部署时人工放入本目录。

## 放置方式（任选其一）

1. **放进本目录（推荐）**
   把 `ojdbc11.jar` / `DmJdbcDriver18.jar` / `db2jcc4.jar` 拷到 `<APP_HOME>/lib/`，
   Spring Boot loader 的 `loader.path` 默认包含 `lib/`，启动即可被发现。

2. **启动参数指定**
   ```bash
   java -Dloader.path=/opt/drivers -jar db-sync-server.jar
   ```

3. **自行加 Maven 依赖**（有私服且合法持证时）
   在 `db-sync-server/pom.xml` 增加对应依赖即可；本工程不会替你引入。

## 驱动类名（平台按此反射加载）

| 数据库 | 驱动类 | 默认端口 |
|---|---|---|
| Oracle 11g/19c | `oracle.jdbc.OracleDriver` | 1521 |
| 达梦 DM8 | `dm.jdbc.driver.DmDriver` | 5236 |
| IBM DB2 LUW 11.5 | `com.ibm.db2.jcc.DB2Driver` | 50000 |
| MySQL 8 | `com.mysql.cj.jdbc.Driver`（已内置） | 3306 |
| PostgreSQL 12+ | `org.postgresql.Driver`（已内置） | 5432 |

## 自检

驱动缺失时，任何需要连该库的接口都会返回结构化错误码 **E7001**（并列出当前 classpath
可用的驱动清单），不会 500 裸堆栈。可用下面两步确认：

```bash
curl -s localhost:8080/api/openapi.json | head        # 服务是否起来
curl -s -X POST localhost:8080/api/datasources/1/test -H "X-DBSync-Token: <token>"
```
