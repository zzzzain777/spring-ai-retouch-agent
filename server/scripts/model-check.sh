#!/usr/bin/env bash
# 用 application-local.yml（或环境变量）里的真实密钥调用一次规划模型。不需要数据库、Redis 或对象存储。
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -q -B spring-boot:run -Dspring-boot.run.main-class=io.github.zzzzain777.modelcheck.ModelCheckApplication "$@"
