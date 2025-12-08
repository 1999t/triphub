#!/bin/bash

# Simple init script for seckill activity and Redis stock.
# This script is intended for local dev / QA use only.

set -e

MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
MYSQL_PORT=${MYSQL_PORT:-3306}
MYSQL_USER=${MYSQL_USER:-root}
MYSQL_PASSWORD=${MYSQL_PASSWORD:-123456}
MYSQL_DB=${MYSQL_DB:-triphub}

REDIS_CLI=${REDIS_CLI:-redis-cli}

ACTIVITY_ID=${ACTIVITY_ID:-1}
ACTIVITY_TITLE=${ACTIVITY_TITLE:-成都酒店秒杀}
PLACE_ID=${PLACE_ID:-1}
STOCK=${STOCK:-50}

echo "Initializing seckill activity in MySQL..."

mysql --protocol=tcp -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "${MYSQL_DB}" <<EOF
INSERT INTO seckill_activity (id, title, place_id, stock, begin_time, end_time, status)
VALUES (
  ${ACTIVITY_ID},
  '${ACTIVITY_TITLE}',
  ${PLACE_ID},
  ${STOCK},
  DATE_SUB(NOW(), INTERVAL 1 DAY),      -- 开始时间设置为一天前，避免出现“Seckill has not started”
  DATE_ADD(NOW(), INTERVAL 1 DAY),      -- 结束时间为一天后
  1
)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  place_id = VALUES(place_id),
  stock = VALUES(stock),
  begin_time = VALUES(begin_time),
  end_time = VALUES(end_time),
  status = VALUES(status);
EOF

echo "Initializing seckill stock in Redis..."

${REDIS_CLI} SET "seckill:stock:${ACTIVITY_ID}" "${STOCK}"

echo "Done. Activity id=${ACTIVITY_ID}, stock=${STOCK}."


