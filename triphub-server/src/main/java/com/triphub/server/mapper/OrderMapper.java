package com.triphub.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.triphub.pojo.entity.Order;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}


