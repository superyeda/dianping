package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;
    /**
     * 根据Id查询blog
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) return Result.fail("博客不存在");

        //获取发布者信息
        User author = userService.getById(blog.getUserId());
        blog.setIcon(author.getIcon());
        blog.setName(author.getNickName());

        // 查询自己是否赞过
        Long userId = UserHolder.getUser().getId();
        if (userId == null) return null;
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
        return Result.ok(blog);
    }

    /**
     * 给博客点赞
     * @param id
     * @return
     */
    @Override
    public Result updateLike(Long id) {
        Long userId = UserHolder.getUser().getId();
        if (userId==null) return Result.fail("用户未登录");
        String key = BLOG_LIKED_KEY + id;
        // TODO：这里缓存和数据库操作是不是可以优化
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null){
            // 为点赞更新赞
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess)
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
        }else{
            // 已经点赞取消
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess)
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
        }
        return Result.ok();
    }

    /**
     * 获取点赞列表
     * @param id
     * @return
     */
    @Override
    public Result getLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 6);
        if (top5==null || top5.isEmpty()) return Result.ok(Collections.emptyList());
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String userIdsStr = StrUtil.join(",", userIds);
        List<UserDTO> UserDTOs = userService.lambdaQuery()
                .in(User::getId, userIds)
                .last("order by field(id," + userIdsStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class)
                ).collect(Collectors.toList());

        return Result.ok(UserDTOs);
    }

    /**
     * 发布博客并推流
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 1. 保存博客到数据库
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        this.save(blog);
        // 2. feed推流
        //  2.1 查询粉丝id
        List<Follow> list = followService.list(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getFollowUserId, userId));
        //  2.2 向对应的粉丝推送blogId
        for (Follow follow : list) {
            Long followId = follow.getUserId();
            String key = "blog:" + followId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    /**
     * TODO: 需要理解业务实现逻辑
     * 查询关注的人发布的blog
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        // 1. 查询自己的收件箱
        String key = "blog:" + userId;
        // TODO：需要理解一下操作和解析数据部分内容
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        if(typedTuples == null || typedTuples.isEmpty()) return Result.ok();

        // 2.解析数据 TODO:数据类型ArrayList和List
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime =0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String blogId = typedTuple.getValue();
            ids.add(Long.valueOf(blogId));
            long time = typedTuple.getScore().longValue();
            if(time == minTime)os++;
            else{
                minTime = time;
                os=1;
            }
        }
        // 根据id查询blog
        ArrayList<Blog> blogs = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Blog blog = getById(id);
            blogs.add(blog);
        }
        blogs.forEach(this::isBlogLiked);
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);

        return Result.ok(scrollResult);
    }

    private void isBlogLiked(Blog blog) {
        //获取当前登陆用户
        UserDTO user = UserHolder.getUser();
        if (user==null){
            return;
        }
        Long userId = user.getId();
        //判断当前用户时候点赞
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }
}
