package com.dataocean.module.user.service.impl;

import com.dataocean.module.user.entity.vo.CaptchaVO;
import com.dataocean.module.user.service.CaptchaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaServiceImpl implements CaptchaService {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String REDIS_PREFIX = "captcha:";
    private static final long EXPIRE_MINUTES = 5;
    private static final int CODE_LENGTH = 4;
    private static final int WIDTH = 130;
    private static final int HEIGHT = 48;
    private static final String CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    @Override
    public CaptchaVO generate() {
        String code = randomCode();
        String key = UUID.randomUUID().toString().replace("-", "");
        String image = drawImage(code);

        stringRedisTemplate.opsForValue().set(REDIS_PREFIX + key, code, EXPIRE_MINUTES, TimeUnit.MINUTES);
        log.debug("验证码已生成 key={}", key);

        CaptchaVO vo = new CaptchaVO();
        vo.setCaptchaKey(key);
        vo.setCaptchaImage("data:image/png;base64," + image);
        return vo;
    }

    @Override
    public boolean verify(String captchaKey, String captchaCode) {
        if (captchaKey == null || captchaCode == null) {
            return false;
        }
        String redisKey = REDIS_PREFIX + captchaKey;
        String stored = stringRedisTemplate.opsForValue().get(redisKey);
        // 验证后立即删除，防止重复使用
        stringRedisTemplate.delete(redisKey);

        if (stored == null) {
            return false;
        }
        return stored.equalsIgnoreCase(captchaCode.trim());
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private String drawImage(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // 背景
        g.setColor(new Color(245, 245, 245));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // 干扰线
        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(random.nextInt(200), random.nextInt(200), random.nextInt(200)));
            g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                    random.nextInt(WIDTH), random.nextInt(HEIGHT));
        }

        // 噪点
        for (int i = 0; i < 30; i++) {
            g.setColor(new Color(random.nextInt(200), random.nextInt(200), random.nextInt(200)));
            g.fillOval(random.nextInt(WIDTH), random.nextInt(HEIGHT), 2, 2);
        }

        // 绘制字符
        g.setFont(new Font("Arial", Font.BOLD, 30));
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(random.nextInt(80), random.nextInt(80), random.nextInt(150)));
            int x = 12 + i * 28;
            int y = 32 + random.nextInt(6) - 3;
            double angle = (random.nextDouble() - 0.5) * 0.3;
            g.rotate(angle, x, y);
            g.drawString(String.valueOf(code.charAt(i)), x, y);
            g.rotate(-angle, x, y);
        }

        g.dispose();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            log.error("验证码图片生成失败", e);
            return "";
        }
    }
}
