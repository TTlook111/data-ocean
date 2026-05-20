package com.dataocean.module.user.service;

import com.dataocean.module.user.entity.vo.CaptchaVO;

public interface CaptchaService {

    CaptchaVO generate();

    boolean verify(String captchaKey, String captchaCode);
}
