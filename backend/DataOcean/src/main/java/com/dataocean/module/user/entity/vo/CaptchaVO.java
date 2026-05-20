package com.dataocean.module.user.entity.vo;

import lombok.Data;

@Data
public class CaptchaVO {

    private String captchaKey;
    private String captchaImage;
}
