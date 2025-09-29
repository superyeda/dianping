package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.convert.RedisData;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;


@Component
@Slf4j
public class CacheClient {

    /**
     * 将任意对象序列化成json存入redis
     *
     * @param key   关键
     * @param value 价值
     * @param time  时间
     * @param unit  单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    /**
     * 将任意对象序列化成json存入redis 并且携带逻辑过期时间
     *
     * @param key   关键
     * @param value 价值
     * @param time  时间
     * @param unit  单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //封装逻辑过期时间
        RedisDate redisDate = new RedisDate();
        redisDate.setData(value);
        redisDate.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //存入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisDate));
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 设置空值解决缓存穿透
     *
     * @param keyPrefix  关键前缀
     * @param id         id
     * @param type       类型
     * @param dbFallback db回退
     * @param time       时间
     * @param unit       单位
     * @return {@link R}
     */
    public <R, ID> R queryWithSaveNull(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID,R> dbFallback,
            Long time,
            TimeUnit unit

        ){
        String key = keyPrefix + id;
        // 1. 查询缓存中是否存在数据
        String json = stringRedisTemplate.opsForValue().get(key);
        R r = null;
        // 1.1 读取到缓存，直接返回
        if (StrUtil.isNotBlank(json)) {
            r = JSONUtil.toBean(json,type);
            return r;
        }
        if (json != null) return null;
        // 查询数据库
        r = dbFallback.apply(id);
        if (r == null) {
            this.set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
            return null;
        }
        //数据库存在 写入redis
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * TODO:待补充
     * 利用互斥锁解决缓存击穿问题
     * @param id
     * @return
     */
    /*private <R,ID> R queryWithLock(
            R
            R id){
        String key = CACHE_SHOP_KEY + id;
        // 1. 查询缓存中是否存在数据
        String json = stringRedisTemplate.opsForValue().get(key);
        // 1.1 读取到缓存，直接返回
        try {
            if (StrUtil.isNotBlank(json)) return JSONUtil.toBean(json, Shop.class);
        } catch (Exception e) {
            log.debug("---------:{},{},{}",json.getClass(),json,json.length());
            throw new RuntimeException(e);
        }


        if ("".equals(json)) return null;
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
    }*/


    /**
     * TODO: 使用逻辑过期时间，未命中直接返回空问题
     * 利用逻辑过期解决缓存击穿问题
     * 大致流程：缓存查询 ——> 逻辑过期时间 ——> 获得锁 ——> 开启新线程操作数据库 ——> 重建缓存加入逻辑过期时间
     *                                   |
     *                                   ——> 获取不到锁 ——> 返回过期数据
     */
    public <R,ID> R queryWithLogicalEx(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID,R> dbFallback,
            Long time,
            TimeUnit unit){
        String key = keyPrefix + id;
        // 1. 查询缓存中是否存在数据
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json))return null;
        RedisDate redisDate = JSONUtil.toBean(json, RedisDate.class);
        JSONObject jsonObject =(JSONObject) redisDate.getData();
        R r = BeanUtil.toBean(jsonObject, type);
        LocalDateTime expireTime = redisDate.getExpireTime();
        // 未过期直接返回
        if(expireTime.isAfter(LocalDateTime.now()))return r;
        // 过期重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        log.info("isLock:{}",isLock);
        if(isLock){
            // 异步重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R newR = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,newR,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        return r;

    }

    /**
     * 简易线程池
     */
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    /**
     * 获取锁
     *
     * @param key 关键
     * @return boolean
     */
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key 关键
     */
    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
