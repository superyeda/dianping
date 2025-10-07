package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;
    /**
     * 关注或取关
     * @param id
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long id, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (isFollow) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            this.save(follow);
        }else{
            //TODO: LambdaQueryWrapper什么意思
            this.remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getFollowUserId,id)
                    .eq(Follow::getUserId,userId));
        }
        return Result.ok();
    }

    /**
     * 查询是否关注
     * @param id
     * @return
     */
    @Override
    public Object isFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        Long count = lambdaQuery()
                .eq(Follow::getFollowUserId, id)
                .eq(Follow::getUserId, userId)
                .count();
        return Result.ok(count>0);
    }

    /**
     * 共同关注列表
     * @param id
     * @return
     */
    @Override
    public Result commonFollows(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        //TODO:为什么使用collect(Collectors.toList());
        if (intersect == null || intersect.isEmpty()) return Result.ok(Collections.emptyList());
        // 解析ID
        List<Long> userIds = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查询用户
        List<User> users = userService.listByIds(userIds);
        List<UserDTO> userDTOs = users.stream().map(user ->
                        BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOs);
    }
}
