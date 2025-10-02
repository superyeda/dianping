package com.hmdp.service.impl;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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
    @Override
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

        // 3. 加锁
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        boolean isLock = simpleRedisLock.tryLock(60L);
        if(!isLock) return Result.fail("限购一单");
        try {
            return this.getResult(voucherId);
        } finally {
            simpleRedisLock.unLock();
        }
    }


    /**
     * 一人一单业务
     * 重复购买判断 ——> 更新库存（乐观锁）——> 订单创建
     * @param voucherId
     * @return
     */
    private Result getResult(Long voucherId){
        // 1. 是否下单
        Long userId = UserHolder.getUser().getId();
        Long count = lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .eq(VoucherOrder::getUserId, userId)
                .count();
        if (count>0) return Result.fail("限购一单");

        // 2. 构建库存（乐观锁）
        boolean flag = seckillVoucherService.update(
                new LambdaUpdateWrapper<SeckillVoucher>()
                        .eq(SeckillVoucher::getVoucherId, voucherId)
                        .gt(SeckillVoucher::getStock, 0)
                        .setSql("stock = stock - 1")
        );
        if (!flag) return Result.fail("库存不足");

        // 3. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        this.save(voucherOrder);
        return Result.ok(orderId);
    }
}
