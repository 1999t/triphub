package com.triphub.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.triphub.pojo.entity.Trip;

public interface TripService extends IService<Trip> {

    Trip queryTripById(Long id);

    /**
     * Called when a trip detail is viewed: increase view count and update hot trips leaderboard.
     */
    void increaseViewCountAndHotScore(Trip trip);
}


