#!/usr/bin/env bash
# 真实库 E2E 冒烟：验证「MySQL 8 元数据库 + Flyway V1~V4 + 服务启动 + 登录鉴权」这条生产前提。
# 737 次自动化测试全部跑在 H2 上，本脚本补的是 H2 覆盖不到的那一层。
# 前置：docker compose up -d --build（见 docker-compose.yml）
set -euo pipefail

BASE="${BASE_URL:-http://127.0.0.1:8080}"
MAX_WAIT="${MAX_WAIT_SECONDS:-180}"
COMPOSE="${COMPOSE_CMD:-docker compose}"

echo "==> 1/4 等待服务健康 ($BASE/actuator/health)，最多 ${MAX_WAIT}s"
ok=0
for i in $(seq 1 $((MAX_WAIT / 5))); do
  if curl -fsS "$BASE/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    ok=1; break
  fi
  sleep 5
done
if [ "$ok" -ne 1 ]; then
  echo "FAIL: 服务未就绪。可查看日志：${COMPOSE} logs app" >&2
  exit 1
fi
echo "    OK: UP"

echo "==> 2/4 在真实 MySQL 8 上核对 Flyway 迁移"
migrations=$(${COMPOSE} exec -T mysql \
  mysql -uroot -proot_dev -N -B -e \
  "SELECT COUNT(*) FROM dbsync_meta.flyway_schema_history WHERE success = 1" 2>/dev/null | tr -d '\r')
failed=$(${COMPOSE} exec -T mysql \
  mysql -uroot -proot_dev -N -B -e \
  "SELECT COUNT(*) FROM dbsync_meta.flyway_schema_history WHERE success = 0" 2>/dev/null | tr -d '\r')
echo "    成功迁移: ${migrations:-0}  失败迁移: ${failed:-0}"
if [ "${failed:-1}" -ne 0 ] || [ "${migrations:-0}" -lt 4 ]; then
  echo "FAIL: 期望 V1~V4 全部成功且无失败记录" >&2
  exit 1
fi

echo "==> 3/4 登录（默认管理员，来自 V2__seed.sql）"
login=$(curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@12345"}')
token=$(printf '%s' "$login" | grep -oE '"token":"[^"]+"' | head -1 | cut -d'"' -f4 || true)
if [ -z "$token" ]; then
  echo "FAIL: 登录未返回 token，响应：$login" >&2
  exit 1
fi
echo "    OK: 取得令牌"

echo "==> 4/4 带令牌读取接口目录（验证 RBAC 白名单与目录可用）"
code=$(curl -sS -o /tmp/dbsync-openapi.json -w '%{http_code}' \
  -H "X-DBSync-Token: $token" "$BASE/api/openapi.json")
if [ "$code" != "200" ] || ! grep -q '/api/' /tmp/dbsync-openapi.json; then
  echo "FAIL: /api/openapi.json 返回 $code 或内容异常" >&2
  exit 1
fi
endpoints=$(grep -oE '"/api/[^"]+"' /tmp/dbsync-openapi.json | sort -u | wc -l | tr -d ' ')
echo "    OK: HTTP 200，目录含 ${endpoints} 个 /api 路径"

cat <<'EOF'

PASS: 真实 MySQL 8 元数据库 + Flyway + 鉴权链路可用。
提示：MailHog Web UI 在 http://127.0.0.1:8025，可用来验证邮件告警是否真的发出。
清理：docker compose down -v
EOF
