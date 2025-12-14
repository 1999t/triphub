package com.triphub.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.triphub.pojo.dto.TripSummaryDTO;
import com.triphub.pojo.entity.Trip;

import java.util.List;

public interface TripService extends IService<Trip> {

    Trip queryTripById(Long id);

    /**
     * Called when a trip detail is viewed: increase view count and update hot trips leaderboard.
     */
    void increaseViewCountAndHotScore(Trip trip);

    /**
     * 发现页/榜单场景使用的行程摘要列表：
     * - 优先从 Redis 摘要缓存读取，减少 DB 压力；
     * - 叠加 Redis 中尚未落库的 view_count 增量（trip:view:delta），保证展示更“实时”；
     * - 仅返回公开行程摘要（visibility=2，历史数据 null 视为公开）。
     *
     * @param orderedIds 热榜/推荐候选的有序 id 列表
     */
    List<TripSummaryDTO> listPublicTripSummariesForDiscover(List<Long> orderedIds);
}


