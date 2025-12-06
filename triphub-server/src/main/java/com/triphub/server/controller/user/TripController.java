package com.triphub.server.controller.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.triphub.common.context.BaseContext;
import com.triphub.common.result.PageResult;
import com.triphub.common.result.Result;
import com.triphub.pojo.dto.TripDayNoteDTO;
import com.triphub.pojo.dto.TripItemCreateDTO;
import com.triphub.pojo.dto.TripItemUpdateDTO;
import com.triphub.pojo.entity.Trip;
import com.triphub.pojo.entity.TripDay;
import com.triphub.pojo.entity.TripItem;
import com.triphub.pojo.vo.TripDayDetailVO;
import com.triphub.server.service.TripDayService;
import com.triphub.server.service.TripItemService;
import com.triphub.server.service.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/user/trip")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;
    private final TripDayService tripDayService;
    private final TripItemService tripItemService;

    /**
     * Create a trip (simple version): only save basic trip info.
     */
    @PostMapping
    public Result<Long> createTrip(@RequestBody Trip trip) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        trip.setUserId(userId);
        tripService.save(trip);
        return Result.success(trip.getId());
    }

    /**
     * Trip detail with cache.
     */
    @GetMapping("/{id}")
    public Result<Trip> getTrip(@PathVariable("id") Long id) {
        Trip trip = tripService.queryTripById(id);
        if (trip == null) {
            return Result.error("行程不存在");
        }
        // Increase view count and write into hot trips leaderboard
        tripService.increaseViewCountAndHotScore(trip);
        return Result.success(trip);
    }

    /**
     * Current user's trip list with pagination.
     */
    @GetMapping("/list")
    public Result<PageResult> listMyTrips(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "10") int size) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        Page<Trip> p = new Page<>(page, size);
        LambdaQueryWrapper<Trip> wrapper = new LambdaQueryWrapper<Trip>()
                .eq(Trip::getUserId, userId)
                .orderByDesc(Trip::getCreateTime);
        tripService.page(p, wrapper);
        PageResult pr = new PageResult(p.getTotal(), p.getRecords());
        return Result.success(pr);
    }

    /**
     * View trip plan of a specific day (with item list).
     */
    @GetMapping("/day/detail")
    public Result<TripDayDetailVO> getTripDayDetail(@RequestParam Long tripId,
                                                    @RequestParam Integer dayIndex) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        if (tripId == null || dayIndex == null || dayIndex <= 0) {
            return Result.error("参数错误");
        }

        Trip trip = tripService.getById(tripId);
        if (trip == null || !userId.equals(trip.getUserId())) {
            return Result.error("行程不存在或无权访问");
        }
        if (trip.getDays() != null && dayIndex > trip.getDays()) {
            return Result.error("行程天数超出范围");
        }

        TripDay day = tripDayService.getOne(new LambdaQueryWrapper<TripDay>()
                .eq(TripDay::getTripId, tripId)
                .eq(TripDay::getDayIndex, dayIndex));

        TripDayDetailVO vo = new TripDayDetailVO();
        vo.setTripId(tripId);
        vo.setDayIndex(dayIndex);

        if (day != null) {
            vo.setId(day.getId());
            vo.setNote(day.getNote());
            List<TripItem> items = tripItemService.list(new LambdaQueryWrapper<TripItem>()
                    .eq(TripItem::getTripDayId, day.getId())
                    .orderByAsc(TripItem::getStartTime)
                    .orderByAsc(TripItem::getId));
            vo.setItems(items);
        } else {
            vo.setItems(Collections.emptyList());
        }

        return Result.success(vo);
    }

    /**
     * Set or update note of a specific trip day (create if not exists).
     */
    @PutMapping("/day/note")
    public Result<Void> updateTripDayNote(@RequestBody TripDayNoteDTO dto) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        if (dto == null || dto.getTripId() == null || dto.getDayIndex() == null || dto.getDayIndex() <= 0) {
            return Result.error("参数错误");
        }

        Long tripId = dto.getTripId();
        Integer dayIndex = dto.getDayIndex();

        Trip trip = tripService.getById(tripId);
        if (trip == null || !userId.equals(trip.getUserId())) {
            return Result.error("行程不存在或无权访问");
        }
        if (trip.getDays() != null && dayIndex > trip.getDays()) {
            return Result.error("行程天数超出范围");
        }

        TripDay day = tripDayService.getOne(new LambdaQueryWrapper<TripDay>()
                .eq(TripDay::getTripId, tripId)
                .eq(TripDay::getDayIndex, dayIndex));
        if (day == null) {
            day = new TripDay();
            day.setTripId(tripId);
            day.setDayIndex(dayIndex);
            day.setNote(dto.getNote());
            tripDayService.save(day);
        } else {
            day.setNote(dto.getNote());
            tripDayService.updateById(day);
        }

        return Result.success(null);
    }

    /**
     * Create a trip item by (tripId + dayIndex).
     */
    @PostMapping("/item")
    public Result<Long> createTripItem(@RequestBody TripItemCreateDTO dto) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        if (dto == null || dto.getTripId() == null || dto.getDayIndex() == null || dto.getDayIndex() <= 0) {
            return Result.error("参数错误");
        }

        Long tripId = dto.getTripId();
        Integer dayIndex = dto.getDayIndex();

        Trip trip = tripService.getById(tripId);
        if (trip == null || !userId.equals(trip.getUserId())) {
            return Result.error("行程不存在或无权访问");
        }
        if (trip.getDays() != null && dayIndex > trip.getDays()) {
            return Result.error("行程天数超出范围");
        }

        TripDay day = tripDayService.getOne(new LambdaQueryWrapper<TripDay>()
                .eq(TripDay::getTripId, tripId)
                .eq(TripDay::getDayIndex, dayIndex));
        if (day == null) {
            day = new TripDay();
            day.setTripId(tripId);
            day.setDayIndex(dayIndex);
            tripDayService.save(day);
        }

        TripItem item = new TripItem();
        item.setTripDayId(day.getId());
        item.setType(dto.getType());
        item.setPlaceId(dto.getPlaceId());
        item.setStartTime(dto.getStartTime());
        item.setEndTime(dto.getEndTime());
        item.setMemo(dto.getMemo());
        tripItemService.save(item);

        return Result.success(item.getId());
    }

    /**
     * Update a trip item.
     */
    @PutMapping("/item")
    public Result<Void> updateTripItem(@RequestBody TripItemUpdateDTO dto) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        if (dto == null || dto.getId() == null) {
            return Result.error("参数错误");
        }

        TripItem item = tripItemService.getById(dto.getId());
        if (item == null) {
            return Result.error("行程条目不存在");
        }

        TripDay day = tripDayService.getById(item.getTripDayId());
        if (day == null) {
            return Result.error("所属行程日不存在");
        }

        Trip trip = tripService.getById(day.getTripId());
        if (trip == null || !userId.equals(trip.getUserId())) {
            return Result.error("行程不存在或无权访问");
        }

        item.setType(dto.getType());
        item.setPlaceId(dto.getPlaceId());
        item.setStartTime(dto.getStartTime());
        item.setEndTime(dto.getEndTime());
        item.setMemo(dto.getMemo());
        tripItemService.updateById(item);

        return Result.success(null);
    }

    /**
     * Delete a trip item.
     */
    @DeleteMapping("/item/{id}")
    public Result<Void> deleteTripItem(@PathVariable("id") Long id) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            return Result.error("未登录或 token 无效");
        }
        if (id == null) {
            return Result.error("参数错误");
        }

        TripItem item = tripItemService.getById(id);
        if (item == null) {
            return Result.success(null);
        }

        TripDay day = tripDayService.getById(item.getTripDayId());
        if (day == null) {
            tripItemService.removeById(id);
            return Result.success(null);
        }

        Trip trip = tripService.getById(day.getTripId());
        if (trip == null || !userId.equals(trip.getUserId())) {
            return Result.error("行程不存在或无权访问");
        }

        tripItemService.removeById(id);
        return Result.success(null);
    }
}


