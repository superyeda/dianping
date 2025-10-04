package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.RegexUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.ResponseMsgConstants.CODE_ERR;
import static com.hmdp.utils.ResponseMsgConstants.PHONE_ERR;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@SpringBootTest
public class createUserTests{

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UserServiceImpl userService;
    /**
     * 创建200个用户，并将其token保存至文件中
     */
    @Test
    public void creteUser(){
        Long intialPhone = 18800000001L;
        ArrayList<String> tokens = new ArrayList<>();

        for (int i = 0; i < 200; i++) {
            Long phoneNum =intialPhone + i;
            String phoneStr = phoneNum.toString();
            // 2. 注册
            User user = createUserWithPhone(phoneStr);
            // 3. 生成用户token
            String token = UUID.randomUUID().toString(true);
            // userDTO转map
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            userDTO.setId(1014L+i);
            Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>()
                    , CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor(
                            (name, value) -> value.toString()
                    ));
            stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,map);
            stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL, TimeUnit.MINUTES);
            tokens.add(token);
        }
        // 将token写入txt
        try(FileWriter fileWriter = new FileWriter("tokens.txt",true)){
            for (String token : tokens) {
                fileWriter.write(token);
                fileWriter.write(System.lineSeparator()); // 换行，兼容不同系统
            }
        }catch (IOException e){
            e.printStackTrace();
        }

    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
//        userService.getBaseMapper().insert(user);
        return user;
    }
}
