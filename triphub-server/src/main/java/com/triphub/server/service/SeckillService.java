package com.triphub.server.service;

public interface SeckillService {

    /**
     * User joins seckill activity, returns generated orderId if accepted.
     */
    Long seckill(Long activityId);
}


