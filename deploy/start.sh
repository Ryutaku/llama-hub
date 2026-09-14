#!/bin/bash
# 分离部署启动：纯 class 主 jar + 依赖(lib/) + 外置配置与静态页面(resources/)
# 全部由 jar 内 manifest 加载（Class-Path: ./resources/ + Loader-Path: resources/,lib/），零参数
cd "$(dirname "$0")"
if [ -f app.pid ] && kill -0 "$(cat app.pid)" 2>/dev/null; then
  echo "already running pid=$(cat app.pid)"
  exit 1
fi
mkdir -p logs
nohup /usr/local/jdk-21.0.8/bin/java \
  -jar llm-gateway.jar \
  > logs/console.out 2>&1 &
echo $! > app.pid
echo "started pid=$(cat app.pid)"
