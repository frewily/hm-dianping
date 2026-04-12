package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit  unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit  unit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));//unit.toSeconds(time): 将时间单位转换成秒
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R ,ID> R queryWithPaaThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //1 从redis中查询店铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.1 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //存在，返回
            return JSONUtil.toBean(json, type);
        }
        //2.2 不存在，判断是否为空（下面json就两种情况：null或空字符串）
        if (json != null) {//-->json为空字符串,直接返回错误信息，不再查询数据库，避免缓存穿透
            return null;

        }
        //3 shopJson为null,不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //4 数据库中不存在，返回404
        if (r == null) {
            //4.1 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //4.2 返回错误信息
            return null;
        }
        //5 存在，写入redis
        this.set(key, r, time, unit);
        //6 返回
        return r;
    }

    //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R ,ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //1 从redis中查询店铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2 判断是否存在
        if (StrUtil.isBlank(json)) {
            //3 不存在，返回null
            return null;
        }
        //4 命中，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期，直接返回
            return r;
        }
        //5.2 已过期，需要缓存重建
        //6 缓存重建
        //6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean islock = tryLock(lockKey);
        //6.2 判断是否获取成功
        if (islock) {
            //6.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.set(key, dbFallback.apply(id), time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //7 释放锁
                    unLock(lockKey);
                }
            });
        }
        //8 返回过期的商铺信息
        return r;
    }

    //获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        /**
         * 返回 Boolean（包装类型），有三种可能：
         * true：设置成功（key不存在）
         * false：设置失败（key已存在）
         * null：Redis连接异常或其他错误
         */
        return BooleanUtil.isTrue(flag);// null 时返回 false
    }

    //释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

}
