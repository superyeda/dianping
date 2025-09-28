package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.ResponseMsgConstants.CODE_ERR;
import static com.hmdp.utils.ResponseMsgConstants.PHONE_ERR;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 发送验证码逻辑
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        if(RegexUtils.isPhoneInvalid(phone))return Result.fail("手机号格式不正确");
        String code = RandomUtil.randomNumbers(6);
        // 将验证码存入redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("发送验证码：{}",code);
        return Result.ok();
    }

    /**
     * 用户登录业务逻辑
     * @param loginForm
     * @param session
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone))return Result.fail(PHONE_ERR);
        // 1. 获取缓存中的验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code))return Result.fail(CODE_ERR);
        // 2. 查询用户是否存在，不存在就先注册
        // TODO：LambdaQueryWrapper是什么
//        User user = baseMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        User user = query().eq("phone", phone).one();
        if(user == null)user = createUserWithPhone(phone);
        // 3. 生成用户token
        String token = UUID.randomUUID().toString(true);
        // userDTO转map
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // TODO: 需要理解
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>()
                , CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor(
                        (name, value) -> value.toString()
                ));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,map);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);

    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        baseMapper.insert(user);
        return user;
    }
}
