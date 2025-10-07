package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.ResponseMsgConstants.SHOP_NOT_EXIST;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 根据id查询店铺信息
     * 如何应对：缓存穿透、雪崩和击穿问题
     * @param id
     * @return
     */
    @Override
    public Result shopInfo(Long id) {
        //缓存空对象解决缓存穿透
//        Shop shop = queryWithSaveNull(id);
        // 使用互斥锁解决缓存击穿
//        Shop shop = queryWithLock(id);
        // 使用逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalEx(id);

//        Shop shop = cacheClient.queryWithLogicalEx(
//                CACHE_SHOP_KEY,
//                id, Shop.class,
//                this::getById,
//                LOGIC_EX_TTL,
//                TimeUnit.MINUTES
//        );
        Shop shop = cacheClient.queryWithSaveNull(
                CACHE_SHOP_KEY,
                id, Shop.class,
                this::getById,
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );

        if(shop == null) return Result.fail(SHOP_NOT_EXIST);
        return Result.ok(shop);
    }



/**
 * 缓存穿透
 * 场景：通过请求缓存和数据库中都不存在的数据，一直访问数据库
 * 解决方案：
 * 1. 缓存空对象：缓存和数据库中未读取到数据 -> 缓存短时间过期的空对象 -> 之后访问打到空对象上
 * 2. 布隆过滤：缓存之前加入一层过滤器，判断不存在一定就不存在，判断存在不一定存在
 */

/**
 * 缓存击穿（hot key问题）
 * 场景：高并发的场景下由于Key突然失效，大量请求服务数据库
 * 解决方案:
 * 1. 互斥锁：未读到缓存 ->  上锁  ->  操作DB  -> 解锁
 * 2. 设置逻辑过期：
 */

    /**
     * 利用缓存空对象解决缓存穿透问题
     */
    private Shop queryWithSaveNull(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1. 查询缓存中是否存在数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        Shop shop = null;
        // 1.1 读取到缓存，直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            shop = JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }
        if (shopJson != null) return null;
        // 查询数据库
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        shop = getById(id);
        if (Objects.isNull(shop)) {
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(
            key,
            JSONUtil.toJsonStr(shop),
            CACHE_SHOP_TTL,
            TimeUnit.MINUTES
        );

        return shop;
    }

    /**
     * 利用互斥锁解决缓存击穿问题
     * 大致流程：互斥锁：未读到缓存 ->  上锁  ->  操作DB  -> 解锁
     * @param id
     * @return
     */
    private Shop queryWithLock(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1. 查询缓存中是否存在数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 1.1 读取到缓存，直接返回
//         TODO: 判断不全 缓存为空但查询出来是1
        try {
            if (StrUtil.isNotBlank(shopJson)) return JSONUtil.toBean(shopJson, Shop.class);
        } catch (Exception e) {
            log.debug("---------:{},{},{}",shopJson.getClass(),shopJson,shopJson.length());
            throw new RuntimeException(e);
        }


        if ("".equals(shopJson)) return null;
        // 2 缓存中没有数据，通过互斥锁重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = cacheClient.tryLock(key);
            if(!isLock){
                //获取失败休眠并从重试
                Thread.sleep(50);
                return queryWithLock(id);
            }
            // 重建缓存,模拟延迟
            Thread.sleep(200);
            shop = getById(id);
            if(shop == null){
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            cacheClient.unLock(lockKey);
        }
        return shop;
    }



    /**
     * 利用逻辑过期解决缓存击穿问题
     * 大致流程：缓存查询 ——> 逻辑过期时间 ——> 获得锁 ——> 开启新线程操作数据库 ——> 重建缓存加入逻辑过期时间
     *                                   |
     *                                   ——> 获取不到锁 ——> 返回过期数据
     */
    private Shop queryWithLogicalEx(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1. 查询缓存中是否存在数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isEmpty(shopJson))return null;
        // TODO:多次转换需要理解 命中 反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject jsonObject =(JSONObject) redisData.getData();
        Shop shop = BeanUtil.toBean(jsonObject, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 未过期直接返回
        if(expireTime.isAfter(LocalDateTime.now()))return shop;
        // 过期重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = cacheClient.tryLock(lockKey);
        if(isLock){
            // 异步重建
            cacheClient.CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShopToRedis(id,20L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    cacheClient.unLock(lockKey);
                }
            });
        }
        return shop;

    }
    /**
     * 存入redis 携带逻辑过期时间
     */
    public void saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写了redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    /**
     * TODO：需要理解里面的api接口和业务逻辑
     * 根据商铺类型分页查询商铺信息
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x == null || y == null){
            Page<Shop> page = lambdaQuery()
                    .eq(Shop::getTypeId, typeId)
                    .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
            return Result.ok(page);
        }
        //计算分页参数
        int from = (current - 1) * SystemConstants.MAX_PAGE_SIZE;
        int end = current * SystemConstants.MAX_PAGE_SIZE;

        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        //解析出id
        if (results==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size()<from){
            //没有下一页
            return Result.ok();
        }

        //截取
        List<Long> ids=new ArrayList<>(content.size());
        Map<String,Distance> distanceMap=new HashMap<>();
        content.stream().skip(from).forEach(result->{
            //店铺id
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            //距离
            Distance distance = result.getDistance();
            distanceMap.put(shopId,distance);
        });
        //根据id查询shop
        String join = StrUtil.join(",", ids);
        List<Shop> shopList = lambdaQuery().in(Shop::getId, ids).last("order by field(id,"+join+")").list();
        for (Shop shop : shopList) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shopList);
    }
}

