package com.triphub.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.triphub.pojo.entity.User;

public interface UserService extends IService<User> {

    String loginByPhone(String phone, String code);
}


