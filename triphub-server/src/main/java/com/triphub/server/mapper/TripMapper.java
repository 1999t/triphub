package com.triphub.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.triphub.pojo.entity.Trip;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TripMapper extends BaseMapper<Trip> {

    /**
     * 将 view_count 增量刷回 DB：view_count = view_count + delta
     */
    @Update("UPDATE trip SET view_count = view_count + #{delta} WHERE id = #{id}")
    int updateViewCountDelta(@Param("id") Long id, @Param("delta") Long delta);
}


