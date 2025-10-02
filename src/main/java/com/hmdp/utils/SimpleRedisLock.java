package com.hmdp.utils;

import cn.hutool.core.io.resource.StringResource;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements Lock{
    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private String name;

    public static final String KEY_PREFIX = "lock:";

    // 区分不同JVM实例并发
    public static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";


    /*
    * 初始化LUA脚本
    * */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("lua/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    @Override
    public boolean tryLock(Long ttl) {
        // 区分同一JVM实例并发
        String threadId = Thread.currentThread().getId() + "";
        String lockValue = ID_PREFIX + threadId;
        Boolean result = stringRedisTemplate.opsForValue()
                .setIfAbsent(
                        KEY_PREFIX + name,
                        lockValue,
                        ttl,
                        TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public void unLock() {
//        String lockKey = KEY_PREFIX + name;
//        String localLockValue = ID_PREFIX + Thread.currentThread().getId();
//        String redisLockValue = stringRedisTemplate.opsForValue().get(lockKey);
//        if (StrUtil.equals(localLockValue,redisLockValue)) {
//            stringRedisTemplate.delete(lockKey);
//        }
        // 使用lua脚本释放锁，保证get和del的原子性
        // TODO: LUA脚本怎么写、JAVA如何调用LUA脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
}
