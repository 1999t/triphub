-- TripHub 初始化数据库脚本
-- 适用于本地 MySQL 或 Docker MySQL 初始化

-- 1. 创建数据库
CREATE DATABASE IF NOT EXISTS triphub
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;

USE triphub;

-- 2. 用户表（与 com.triphub.pojo.entity.User 对应）
CREATE TABLE IF NOT EXISTS `user` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `phone`        VARCHAR(20)  NOT NULL                COMMENT '手机号',
  `nickname`     VARCHAR(50)  DEFAULT NULL            COMMENT '昵称',
  `avatar`       VARCHAR(255) DEFAULT NULL            COMMENT '头像地址',
  `level`        INT          DEFAULT NULL            COMMENT '会员等级',
  `status`       INT          DEFAULT 1               COMMENT '状态 1-正常 0-禁用',
  `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_user`  BIGINT       DEFAULT NULL            COMMENT '创建人ID',
  `update_user`  BIGINT       DEFAULT NULL            COMMENT '修改人ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 3. 行程主表（Trip）
CREATE TABLE IF NOT EXISTS `trip` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id`         BIGINT       NOT NULL                COMMENT '创建用户ID',
  `title`           VARCHAR(100) NOT NULL                COMMENT '行程标题',
  `destination_city` VARCHAR(100) DEFAULT NULL           COMMENT '目的地城市',
  `start_date`      DATE         DEFAULT NULL            COMMENT '开始日期',
  `end_date`        DATE         DEFAULT NULL            COMMENT '结束日期',
  `days`            INT          DEFAULT NULL            COMMENT '行程天数',
  `visibility`      INT          DEFAULT 0               COMMENT '0私有 1好友可见 2公开',
  `like_count`      INT          DEFAULT 0               COMMENT '点赞数',
  `view_count`      INT          DEFAULT 0               COMMENT '浏览数',
  `create_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_user`     BIGINT       DEFAULT NULL            COMMENT '创建人ID',
  `update_user`     BIGINT       DEFAULT NULL            COMMENT '修改人ID',
  PRIMARY KEY (`id`),
  KEY `idx_trip_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行程主表';

-- 4. 行程天（TripDay）
CREATE TABLE IF NOT EXISTS `trip_day` (
  `id`       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `trip_id`  BIGINT       NOT NULL                COMMENT '行程ID',
  `day_index` INT         NOT NULL                COMMENT '第几天，从1开始',
  `note`     VARCHAR(255) DEFAULT NULL            COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_trip_day_trip` (`trip_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行程每天信息';

-- 5. 行程条目（TripItem）
CREATE TABLE IF NOT EXISTS `trip_item` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `trip_day_id` BIGINT      NOT NULL                COMMENT '行程天ID',
  `type`       VARCHAR(20)  DEFAULT NULL            COMMENT 'SCENIC/HOTEL/FOOD/TRAFFIC',
  `place_id`   BIGINT       DEFAULT NULL            COMMENT '地点ID',
  `start_time` TIME         DEFAULT NULL            COMMENT '开始时间',
  `end_time`   TIME         DEFAULT NULL            COMMENT '结束时间',
  `memo`       VARCHAR(255) DEFAULT NULL            COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_trip_item_day` (`trip_day_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行程条目表';

-- 6. 秒杀活动表（SeckillActivity）
CREATE TABLE IF NOT EXISTS `seckill_activity` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `title`        VARCHAR(100) NOT NULL                COMMENT '活动标题',
  `place_id`     BIGINT       DEFAULT NULL            COMMENT '关联地点ID',
  `stock`        INT          DEFAULT 0               COMMENT '库存',
  `begin_time`   DATETIME     DEFAULT NULL            COMMENT '开始时间',
  `end_time`     DATETIME     DEFAULT NULL            COMMENT '结束时间',
  `status`       INT          DEFAULT 0               COMMENT '状态',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='限时秒杀活动表';

-- 7. 订单表（Order）
CREATE TABLE IF NOT EXISTS `order` (
  `id`                 BIGINT       NOT NULL           COMMENT '订单ID（全局唯一ID）',
  `user_id`            BIGINT       NOT NULL           COMMENT '用户ID',
  `trip_id`            BIGINT       DEFAULT NULL       COMMENT '关联行程ID',
  `seckill_activity_id` BIGINT      DEFAULT NULL       COMMENT '关联秒杀活动ID',
  `status`             INT          DEFAULT 0          COMMENT '0待支付 1已支付 2已取消 3进行中 4已完成',
  `amount`             DECIMAL(10,2) DEFAULT NULL      COMMENT '订单金额',
  `order_time`         DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `pay_time`           DATETIME     DEFAULT NULL       COMMENT '支付时间',
  `cancel_time`        DATETIME     DEFAULT NULL       COMMENT '取消时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_user` (`user_id`),
  KEY `idx_order_activity` (`seckill_activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 8. 用户画像表（UserProfile），用于存储可扩展的画像 JSON
CREATE TABLE IF NOT EXISTS `user_profile` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id`      BIGINT       NOT NULL                COMMENT '用户ID',
  `profile_json` JSON         DEFAULT NULL           COMMENT '用户画像JSON（兴趣标签、预算、偏好等）',
  `create_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_user`  BIGINT       DEFAULT NULL            COMMENT '创建人ID',
  `update_user`  BIGINT       DEFAULT NULL            COMMENT '修改人ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_profile_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户画像表';

