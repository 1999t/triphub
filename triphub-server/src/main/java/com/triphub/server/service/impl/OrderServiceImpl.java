package com.triphub.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.triphub.pojo.entity.Order;
import com.triphub.server.mapper.OrderMapper;
import com.triphub.server.service.OrderService;
import org.springframework.stereotype.Service;

@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {
}


