package com.dataocean.module.user.entity.vo;

import lombok.Data;

/**
 * 验证码返回视图对象。
 */
@Data
public class CaptchaVO {

    private String captchaKey;
    private String captchaImage;
}
