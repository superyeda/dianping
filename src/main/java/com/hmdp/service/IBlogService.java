package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 根据id查询blog
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 给blog点赞
     * @param id
     * @return
     */
    Result updateLike(Long id);

    /**
     * 获取点赞列表
     * @param id
     * @return
     */
    Result getLikes(Long id);

    /**
     * 发布博客
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);

    /**
     * 查询关注的人发布的blog
     * @param max
     * @param offset
     * @return
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
