package com.triphub.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.triphub.pojo.entity.Trip;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TripMapper extends BaseMapper<Trip> {
}


