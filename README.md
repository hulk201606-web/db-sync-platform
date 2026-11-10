# 异构数据库同步与迁移平台（db-sync-platform）

面向国产化替代场景的多库同步迁移平台：支持 **MySQL / Oracle / PostgreSQL / DM8 / DB2** 两两互通，提供存量迁移、增量同步、全量比对、灰度切流四项核心能力。

## 文档导航

| 文档 | 内容 |
|---|---|
| [docs/00-后端功能总览.md](docs/00-后端功能总览.md) | **入口文档**：能力清单、模块架构、REST API 索引、数据模型、状态机、风险 |
| [docs/01-PRD.md](docs/01-PRD.md) | 产品需求、验收标准、页面线框 |
| [docs/02-architecture.md](docs/02-architecture.md) | 架构设计、六条不变式、表结构、方言 SPI |
| [docs/03-wbs.md](docs/03-wbs.md) | 工作量分解与里程碑 |
| [docs/04-impl-tasks.md](docs/04-impl-tasks.md) | 47 个实现任务的施工细则 |

> `01`~`04` 是**设计基线**（v1.0 定稿），若与代码实现有出入，一律以 `00-后端功能总览.md`
> 的 §15~§17 与源码为准。v1.2 已按 `CutoverState` 枚举校正过状态机口径，设计稿的
> `SHADOW/FULL_WRITE/FULL_READ` 八状态链路不再代表实现。

## 工程结构

```
db-sync-platform/
├── db-sync-common        领域模型 / 枚举 / 异常 / 工具
├── db-sync-dialect       方言 SPI + 5 个实现（MySQL/Oracle/PG/DM8/DB2）
├── db-sync-metadata      Flyway 脚本 + 18 张元数据表 DAO
├── db-sync-core          数据源/流式读写/分片/checkpoint/租约/限速
├── db-sync-migration     存量迁移引擎
├── db-sync-cdc           增量通道（触发器日志 / 时间戳 / SDK 双写）
├── db-sync-compare       全量比对与差异修复
├── db-sync-cutover       灰度切流与回滚
├── db-sync-api           REST / RBAC / 审计 / 指标
├── db-sync-server        Spring Boot 启动模块
├── db-sync-console       前端控制台（React + Vite）
├── docs/                 设计文档 + 00 功能总览
├── lib/                  可选 JDBC 驱动（Oracle/DM/DB2，不入 pom）
├── scripts/              真实库冒烟脚本
├── .github/workflows/ci.yml   CI：后端全量测试 / 前端 typecheck+build / 真库 E2E（手动）
├── Dockerfile            两阶段镜像构建
├── docker-compose.yml    MySQL 8 元数据库 + PG 业务库 + MailHog（真实库 E2E）
├── .env.example          生产环境变量清单（含告警两层开关）
└── .gitignore
```

依赖方向单向无环：`server → api → {migration, cdc, compare, cutover} → core → {dialect, metadata, common}`

## 快速开始

```bash
# 编译 + 测试
mvn clean install

# 本地启动（h2 档：内存元数据库 + 演示主密钥，无需外部 MySQL）
cd db-sync-server && mvn spring-boot:run -Dspring-boot.run.profiles=h2

# 真实库联调：先复制 .env.example 为 .env 并改好密钥，然后
docker compose up -d --build       # MySQL 8 元数据库 + PostgreSQL + MailHog + 应用
./scripts/smoke-real-db.sh          # 校验 Flyway 在真 MySQL 8 上跑通 + 登录鉴权
docker compose down -v             # 清理
```

启动后：

- 控制台入口 `http://localhost:8080/`
- 接口验证 `http://localhost:8080/api/tasks`（默认账号 `admin` / `Admin@12345`，生产必须改）
- 健康检查 `http://localhost:8080/actuator/health`

> **主密钥**：默认档从环境变量 `DBSYNC_MASTER_KEY` 读取（≥32 字符，启动强校验），留空即拒绝启动；`h2` 档内置演示密钥仅用于本地。
> **元数据库**：默认档其实是本地 H2 **文件库** `jdbc:h2:file:./data/meta`（单机可用，不支持多实例）；
> 生产**必须**显式设置 `DBSYNC_META_URL` 指向共享 MySQL 8，否则多实例各写各的元数据、重启/换机即丢库。详见 `docs/00-后端功能总览.md` §11.3。
> **告警是两层开关**：`DBSYNC_ALERT_CHANNELS`（默认仅 `LOG`，会过滤掉 WEBHOOK/EMAIL）与 `DBSYNC_ALERT_MAIL_ENABLED`（默认 `false`）须同时打开才会发信；启动时 INFO 会打印生效渠道，配置不全则 WARN。

## 前端控制台

```bash
cd db-sync-console
npm install
npm run dev            # 开发模式，/api 代理到 localhost:8080
npm run build          # 只产出 dist/（本地预览）
npm run build:server   # 产物写入 db-sync-server/src/main/resources/static（交付用）
```

- 访问 `http://localhost:8080/`，默认账号 `admin` / `Admin@12345`（种子数据，生产必须改；首次登录会被要求改密）。
- 无后端时可用 `VITE_USE_MOCK=true npm run dev` 查看全部页面（顶部会显示「演示数据」标记）。
- **生产构建默认禁止任何静默降级**：真实模式下 5xx / 405 / 超时 / 网络错误 / 非 JSON / 200 空 body 一律显式报错，写操作（POST/PUT/DELETE）永不回落到仿真数据，导出失败也不会生成假 CSV；只有 `demo` 模式或显式 `VITE_ALLOW_MOCK_FALLBACK=true` 才允许降级。
- 已内置 SPA 回落：刷新 `/monitor`、`/compare/123` 不会 404。

## 关键约定

- **驱动隔离**：Oracle / DM8 / DB2 驱动不入 `pom.xml`（授权原因），放 `lib/` 后由 `Class.forName` 反射加载；缺失时抛 `E7001`。
- **方言隔离**：`db-sync-dialect` 之外不得出现库专属 SQL（`ROWNUM` / `MERGE` / `ON CONFLICT` / `FETCH FIRST` 等）。
- **不进 ORM**：迁移链路需要精确控制 `fetchSize` / `autoCommit` / 批量绑定，全部原生 JDBC。
- **元数据库**：生产 MySQL 8.0+（依赖 `SKIP LOCKED`，需显式设置 `DBSYNC_META_URL`），测试与 demo 用 H2；自动化测试全跑在 H2 上，真库验收走 `docker compose` + `scripts/smoke-real-db.sh`。
- **告警渠道**：`DBSYNC_ALERT_CHANNELS` 是总开关（默认 `LOG`），邮件另有 `DBSYNC_ALERT_MAIL_ENABLED`；两层都开且 `HOST/FROM/RECIPIENTS` 齐全才发信。
- **调度并行**：`spring.task.scheduling.pool.size=6`，与 6 个 `@Scheduled` 入口一一对应，不再互相阻塞；探活按方言设置驱动专属超时并带整体时限。
- **SDK 接入**：路由快照轮询需具备 `TASK_VIEW` 的服务令牌，平台令牌是 8h 硬 TTL 不滑动，请使用可刷新的 `tokenSupplier`（见 §6.8）。

## 质量基线

`mvn clean test` → **737 次测试执行、0 失败、0 错误、0 跳过**，10 个模块全部 SUCCESS（从工作树重建的干净副本独立复验，不复用工作树 `target`）。当前终值：

| 项 | 实测值 |
|---|---|
| Java 文件 | 468（main 348 + test 120） |
| 测试类 / 测试方法 | 108 个类 / 701 个 `@Test`（参数化展开后 737 次执行） |
| REST 端点 | 75 个（85 个 `@*Mapping` 注解 − 10 个类级 `@RequestMapping`），`/api/openapi.json` 目录 75 条零差集 |
| 前端 | `npm run typecheck` 0 error；`build` / `build:demo` / `build:server` 全通过，生产 `dist` 与后端 `static` 的 JS 产物 md5 一致 |
| 覆盖率 | JaCoCo 0.8.12 已接入，`mvn clean test` 后各模块生成 `target/site/jacoco/index.html`（只出报告，不设 fail 门槛） |

其中：

- `EndToEndSmokeTest` 真起 Spring 容器跑通「登录 → 建数据源 → 建任务 → 生成映射 → 大盘 → 切流计划 → Gate → 审计」全链路；
- `CdcCaptureEndToEndTest` 验证增量闭环「采集 → `sync_change_log` → 消费 → 目标表落行 → 水位线推进 → 重启位点恢复」。

> 自动化测试全部跑在 H2 上；真实 MySQL 8 + PostgreSQL + MailHog 验收走 `docker compose up -d --build` + `scripts/smoke-real-db.sh`（CI 中 `real-db-e2e` 为手动触发）。编排配置与脚本语法已校验通过，但本轮因 Docker Hub 鉴权端点不可达未能实际启动（未创建任何容器），因此**该链路尚未完成真实库验收**，也不等同于客户真实数据库/邮件服务器已验收。

## 环境要求

JDK 17+ · Maven 3.6.3+ · Node 18+（仅前端）· MySQL 8.0+（生产元数据库）· Docker + Docker Compose（真实库 E2E，可选）
