package com.dataocean.module.user.service;

import com.dataocean.module.user.entity.vo.CaptchaVO;

/**
 * 登录验证码服务。
 */
public interface CaptchaService {

    /**
     * 生成验证码。
     *
     * @return 验证码视图
     */
    CaptchaVO generate();

    /**
     * 校验验证码。
     *
     * @param captchaKey  验证码 key
     * @param captchaCode 用户输入的验证码
     * @return true 表示校验通过
     */
    boolean verify(String captchaKey, String captchaCode);
}
