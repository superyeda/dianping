package com.hmdp;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
public class hmdpRedisTests {
    @Resource
    private CacheClient cacheClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Test
    public void setLogicalEx(){
        String key = CACHE_SHOP_KEY + "1";
        String cacheData = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(cacheData)) {
            Shop shop = JSONUtil.toBean(cacheData, Shop.class);
            cacheClient.setWithLogicalExpire(key,shop,1L, TimeUnit.MINUTES);
        }

    }
}
