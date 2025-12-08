package com.triphub.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.triphub.pojo.entity.TripFavorite;

import java.util.List;

/**
 * 行程收藏服务。
 */
public interface TripFavoriteService extends IService<TripFavorite> {

    /**
     * 为指定用户收藏行程（幂等），已收藏则直接返回 true。
     */
    boolean addFavorite(Long userId, Long tripId);

    /**
     * 取消收藏，未收藏则直接返回 true。
     */
    boolean removeFavorite(Long userId, Long tripId);

    /**
     * 获取用户最近收藏的若干行程收藏记录，按时间倒序。
     */
    List<TripFavorite> listRecentFavorites(Long userId, int limit);
}



