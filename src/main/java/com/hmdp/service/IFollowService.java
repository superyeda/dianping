package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注与取关
     * @param id
     * @param isFollow
     * @return
     */
    Result follow(Long id, Boolean isFollow);

    /**
     * 查询是否关注
     * @param id
     * @return
     */
    Object isFollow(Long id);

    /**
     * 共同关注列表
     * @param id
     * @return
     */
    Result commonFollows(Long id);
}
