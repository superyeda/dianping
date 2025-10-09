package com.hmdp;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoLocation;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
public class hmdpRedisTests {
    @Resource
    private CacheClient cacheClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopService shopService;
    @Test
    public void setLogicalEx(){
        String key = CACHE_SHOP_KEY + "1";
        String cacheData = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(cacheData)) {
            Shop shop = JSONUtil.toBean(cacheData, Shop.class);
            cacheClient.setWithLogicalExpire(key,shop,1L, TimeUnit.MINUTES);
        }

    }

    // TODO: 需要理解GEO指令
    // 预热：按分类存入店铺的地址信息
    @Test
    public void setGeo(){
        List<Shop> shopList = shopService.list();
        Map<Long, List<Shop>> shopMap = shopList.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> shopMapEntry : shopMap.entrySet()) {
            Long typeId = shopMapEntry.getKey();
            List<Shop> values = shopMapEntry.getValue();
            String key = SHOP_GEO_KEY + typeId;

            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
            for (Shop shop : values) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),
                            new Point(shop.getX(),shop.getY())
                        ));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }
    // 使用HyperLog记录UV，模拟百万并发
    @Test
    public void uvHyperLog(){
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i%1000;
            values[j] = "user_"+i;
            if (j == 999)
                stringRedisTemplate.opsForHyperLogLog().add("hl2",values);
        }
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println(count);
    }


}
