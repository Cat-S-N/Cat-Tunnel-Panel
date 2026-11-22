#!/usr/bin/env bash
set -euo pipefail

# Cat Tunnel Panel 一键本地构建与远程发布脚本
# 用法:
#   ./deploy.sh frontend          # 仅构建前端
#   ./deploy.sh backend           # 仅构建后端（需本机 Java 21 + Maven）
#   ./deploy.sh all               # 构建前后端
#
# 如需自动上传到服务器，请通过环境变量指定:
#   DEPLOY_HOST=root@your-server DEPLOY_PORT=22 DEPLOY_DIR=/opt/1panel/www/tms-releases/latest ./deploy.sh frontend

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODE="${1:-all}"

build_frontend() {
  echo "==> 构建前端 (vite-frontend)"
  cd "$ROOT_DIR/vite-frontend"
  npm run build
  ls -lh dist/assets/
}

build_backend() {
  echo "==> 构建后端 (springboot-backend)"
  cd "$ROOT_DIR/springboot-backend"
  mvn clean package -DskipTests=false
  ls -lh target/*.jar
}

sync_remote() {
  if [[ -z "${DEPLOY_HOST:-}" || -z "${DEPLOY_DIR:-}" ]]; then
    echo "==> 未设置 DEPLOY_HOST / DEPLOY_DIR，跳过远程同步（构建产物已在本地生成）"
    return 0
  fi
  local port="${DEPLOY_PORT:-22}"
  echo "==> 同步构建产物到 ${DEPLOY_HOST}:${DEPLOY_DIR} (SSH 端口 ${port})"
  ssh -p "$port" "$DEPLOY_HOST" "mkdir -p '$DEPLOY_DIR/frontend'"
  if [[ "$MODE" == "frontend" || "$MODE" == "all" ]]; then
    rsync -az --delete -e "ssh -p $port" "$ROOT_DIR/vite-frontend/dist/" "${DEPLOY_HOST}:${DEPLOY_DIR}/frontend/"
    scp -P "$port" "$ROOT_DIR/vite-frontend/nginx.conf" "${DEPLOY_HOST}:${DEPLOY_DIR}/nginx.conf"
  fi
  if [[ "$MODE" == "backend" || "$MODE" == "all" ]]; then
    scp -P "$port" "$ROOT_DIR/springboot-backend/target/"*.jar "${DEPLOY_HOST}:${DEPLOY_DIR}/admin-0.0.1-SNAPSHOT.jar"
  fi
  echo "==> 同步完成，请在服务器上执行 docker compose up -d --force-recreate 生效"
}

case "$MODE" in
  frontend)
    build_frontend
    sync_remote
    ;;
  backend)
    build_backend
    sync_remote
    ;;
  all)
    build_frontend
    build_backend
    sync_remote
    ;;
  *)
    echo "用法: $0 [frontend|backend|all]"
    exit 1
    ;;
esac
