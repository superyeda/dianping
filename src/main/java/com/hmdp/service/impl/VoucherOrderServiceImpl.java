package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIDWorker redisIDWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;



    @Resource
    private RedissonClient redissonClient;

    /**
     * 加载抢购资格判断的LUA脚本
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


    /**
     * 类初始化完毕立刻执行
     */
    @PostConstruct
    private void init(){
        String queueName = "stream.orders";
        SECKILL_ORDER_EXECUTOR.submit(()->{
            while (true){
                try {
                    //从消息列表中获取订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) continue;
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 下单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认消费
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    handlePendingList();
//                throw new RuntimeException(e);
                }
            }
        });
    }

    private void handlePendingList() {
        String queueName = "stream.orders";
        while (true){
            try {
                //从消息列表中获取订单信息
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                if (list == null || list.isEmpty()) break;

                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 下单
                handleVoucherOrder(voucherOrder);
                // ACK确认消费
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex){
                    throw new RuntimeException(ex);
                }
            }
        }
    }


    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //创建锁对象（兜底）
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if (!isLock) {
            //获取失败,返回错误或者重试
            throw new RuntimeException("发送未知错误");
        }
        try {
            createVoucherOrder(voucherOrder);

        } finally {
            //释放锁
            lock.unlock();
        }
    }

    /**
     * 秒杀券下单业务逻辑
     * 问题：1. 单体高并发超卖问题
     * 乐观锁结局超卖问题
     * @param voucherId
     * @return
     */
    /*@Override
    public Result secKillVoucher(Long voucherId) {
        // 1. 根据id查询，时间和库存
        SeckillVoucher skVoucher = seckillVoucherService.getById(voucherId);
        LocalDateTime beginTime = skVoucher.getBeginTime();
        LocalDateTime endTime = skVoucher.getEndTime();
        Integer stock = skVoucher.getStock();
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(endTime) && now.isBefore(beginTime)) return Result.fail("不在购买时间段");
        if (stock  < 1) return Result.fail("库存不足");

        // 2. 一人一单判断
        Long count = lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .eq(VoucherOrder::getUserId, UserHolder.getUser().getId())
                .count();
        if (count>0) return Result.fail("限购一单");

        // 3. 扣减库存（如何防止高并发超出库存的情况，引入悲观锁和乐观锁）
        boolean flag = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, voucherId)
                        .gt(SeckillVoucher::getStock, 0)
                        .setSql("stock = stock - 1")
        );
        if (!flag) return Result.fail("库存不足");
        // 4. 创建订单，并返回订单号
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        this.save(voucherOrder);
        return Result.ok(orderId);
    }*/

    /**
     * 悲观锁解决超卖问题思路
     * 用户下单 ——> 查询库存和时间 ——> 加锁和TTL（lock:order:vid = userid） ——> 操作数据库 ——> 创建订单号 ——> 返回订单号 ——> 获取当前的锁，判断是否为自己的锁 ——> 释放锁
     *                           |
     *                           ——> 返回失败
     */
  /*  @Override
    public Result secKillVoucher(Long voucherId) {
        // 1. 根据id查询，时间和库存
        SeckillVoucher skVoucher = seckillVoucherService.getById(voucherId);
        LocalDateTime beginTime = skVoucher.getBeginTime();
        LocalDateTime endTime = skVoucher.getEndTime();
        Integer stock = skVoucher.getStock();
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(endTime) && now.isBefore(beginTime)) return Result.fail("不在购买时间段");
        if (stock  < 1) return Result.fail("库存不足");
        Long userId = UserHolder.getUser().getId();
        // 2. 一人一单业务

        // 3. 普通加锁
    *//*    SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        boolean isLock = simpleRedisLock.tryLock(60L);*//*
        //3. 使用Redisson上锁
        *//**
         * redisson可重入原理
         * 获取锁 ——> 标记线程和有效期 ——> 执行业务 ——> 判断锁是否为自己的 ——> 锁计数-1 ——> 重置有效期
         *       |                                            |                 |
         *       |                                             ——> 锁已经释放     ——> 为零，释放锁
         *       |
         *        ——> 通过线程查询是否为自己的 ——> 锁计数 + 1 ——> 设置有效期 ——> 正常业务
         *       |
         *        ——> 获取失败
         *//*
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean isLock = lock.tryLock();
        if(!isLock) return Result.fail("限购一单");
        try {
            return this.getResult(voucherId);
        } finally {
//            simpleRedisLock.unLock();
            lock.unlock();
        }
    }*/

    /**
     * 秒杀券异步优化+消息队列
     * 实现思路：
     * redis预热将库存量和下单信息存入缓存 ——> 用户下单通过缓存信息判断是否有购买资格 ——> 生成订单号 ——> 存入订单消息队列
     *
     * 异步：处理消息队列中的订单 ——>
     * @param voucherId
     * @return
     */
    @Override
    public Result secKillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIDWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString()
        );
        if (result != null && !result.equals(0L))
            return Result.fail(result.intValue() == 2  ? "不能重复下单" : "库存不足");

        return Result.ok(orderId);
    }


    /**
     * 一人一单业务
     * 重复购买判断 ——> 更新库存（乐观锁）——> 订单创建
     * @param voucherId
     * @return
     */
//    private Result getResult(Long voucherId){
//        // 1. 是否下单
//        Long userId = UserHolder.getUser().getId();
//        Long count = lambdaQuery()
//                .eq(VoucherOrder::getVoucherId, voucherId)
//                .eq(VoucherOrder::getUserId, userId)
//                .count();
//        if (count>0) return Result.fail("限购一单");
//
//        // 2. 构建库存（乐观锁）
//        boolean flag = seckillVoucherService.update(
//                new LambdaUpdateWrapper<SeckillVoucher>()
//                        .eq(SeckillVoucher::getVoucherId, voucherId)
//                        .gt(SeckillVoucher::getStock, 0)
//                        .setSql("stock = stock - 1")
//        );
//        if (!flag) return Result.fail("库存不足");
//
//        // 3. 创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        Long orderId = redisIDWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
////        this.save(voucherOrder);
//        return Result.ok(orderId);
//    }
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //扣减库存
        boolean isSuccess = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                        .gt(SeckillVoucher::getStock, 0)
                        .setSql("stock=stock-1"));
        //创建订单
        this.save(voucherOrder);
    }
}
