package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        //Shop shop = queryWithPaaThrough(id);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //7 返回
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1 从redis中查询店铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.1 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在，返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //2.2 不存在，判断是否为空（下面shopJson就两种情况：null或空字符串）
        if (shopJson != null) {//-->shopJson为空字符串,直接返回错误信息，不再查询数据库，避免缓存穿透
            return null;
        }
        //4 实现缓存重建
        //4.1获取互斥锁
        String LockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(LockKey);
            //4.2 判断是否获取成功
            if(!isLock){
                //4.3 获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4 获取锁成功，根据id查询数据库
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);
            //5 数据库中不存在，返回404
            if (shop == null) {
                //5.1 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //5.2 返回错误信息
                return null;
            }
            //6 存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7 释放互斥锁
            unLock(LockKey);
        }
        //8 返回
        return shop;
    }

    public Shop queryWithPaaThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1 从redis中查询店铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.1 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在，返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //2.2 不存在，判断是否为空（下面shopJson就两种情况：null或空字符串）
        if (shopJson != null) {//-->shopJson为空字符串,直接返回错误信息，不再查询数据库，避免缓存穿透
            return null;

        }
        //3 shopJson为null,不存在，根据id查询数据库
        Shop shop = getById(id);
        //4 数据库中不存在，返回404
        if (shop == null) {
            //4.1 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //4.2 返回错误信息
            return null;
        }
        //5 存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //6 返回
        return shop;
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

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
