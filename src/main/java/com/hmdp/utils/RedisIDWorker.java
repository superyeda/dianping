package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static com.hmdp.utils.RedisConstants.ID_PREFIX;

/**
 * 分布式ID生成器
 */
@Component
public class RedisIDWorker {

    private static final long BEGIN_TIMESTAMP = 1640995200L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 获取ID
     * @param keyPrefix
     * @return
     */
    public Long nextId(String keyPrefix){
        // 1. 时间戳 ——> 当前时间-指定的开始时间
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 2. 生成序列号  icr:key:today
        String today = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Long count = stringRedisTemplate.opsForValue().increment(ID_PREFIX + keyPrefix + ":" + today);
        return timestamp << 32 | count;
    }


}
