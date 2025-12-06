package com.triphub.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.triphub.pojo.entity.TripDay;
import com.triphub.server.mapper.TripDayMapper;
import com.triphub.server.service.TripDayService;
import org.springframework.stereotype.Service;

@Service
public class TripDayServiceImpl extends ServiceImpl<TripDayMapper, TripDay> implements TripDayService {
}


