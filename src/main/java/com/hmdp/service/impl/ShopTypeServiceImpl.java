package com.hmdp.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.io.resource.StringResource;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;
import static com.hmdp.utils.ResponseMsgConstants.SHOP_TYPE_NOT_EXIST;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 获取店铺分类
     * @return
     */
    @Override
    public Result getShopType() {
//        List<ShopType> typeList = null;
        // 1. 先获取缓存
        List<String> shopTypeJsonList = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);
        if (shopTypeJsonList != null && !shopTypeJsonList.isEmpty()) {
            ArrayList<ShopType> typeList = new ArrayList<>();
            for (String str : shopTypeJsonList) {
                typeList.add(JSONUtil.toBean(str,ShopType.class));
            }
            return Result.ok(typeList);
        }
        // 2. 查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        if(Objects.isNull(typeList) || typeList.isEmpty())return Result.fail(SHOP_TYPE_NOT_EXIST);
        // 3. 将数据存入缓存
        for (ShopType shopType : typeList) {
            stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(shopType));
        }
        return Result.ok(typeList);
    }


}
