package com.triphub.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.triphub.pojo.entity.TripItem;
import com.triphub.server.mapper.TripItemMapper;
import com.triphub.server.service.TripItemService;
import org.springframework.stereotype.Service;

@Service
public class TripItemServiceImpl extends ServiceImpl<TripItemMapper, TripItem> implements TripItemService {
}


