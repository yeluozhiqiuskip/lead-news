package com.heima.user.controller;

import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.user.dtos.LoginDto;
import com.heima.user.service.ApUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/login")
public class ApUserLoginController {

    @Autowired
    ApUserService apUserService;

    @PostMapping("/login_auth")
    public ResponseResult login(@RequestBody LoginDto dto){
        return apUserService.login(dto);
    }

    @PostMapping("/login_auth1")
    public String login1(@RequestBody LoginDto dto){
        return "connection success";
    }
}
