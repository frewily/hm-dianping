package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private RedisIdWorker redisIdWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //尚未开始
            return Result.fail("秒杀尚未开始");
        }
        //3 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //已经结束
            return Result.fail("秒杀已经结束");
        }
        //4 判断库存是否充足
        if (voucher.getStock() < 1) {
            //库存不足
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {//同一用户加一把锁  intern() 方法返回的是同一个对象，因此两个字符串相等,保证了是同一用户
            //获取代理对象(事务)
            //确保事务生效（只有通过代理对象调用方法，事务才会生效）
            //避免事务没有提交就释放锁的安全问题
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }


    @NonNull
    @Transactional//事务要想生效，必须保证方法被代理（拿到方法的代理对象进行事务处理）
    public Result createVoucherOrder(Long voucherId) {
        //5 一人只能下单一次
        //5.1 查询订单
        boolean exist = query().eq("user_id", UserHolder.getUser().getId())
                .eq("voucher_id", voucherId).count() > 0;
        //5.2 判断是否已经下过单
        if (exist) {
            return Result.fail("用户已经下过单");
        }
        //6 扣减库存
        boolean success = seckillVoucherService
                .update()                                     // ① 创建 UpdateWrapper 对象
                .setSql("stock = stock - 1")                  // ② 设置更新的 SQL 片段
                .eq("voucher_id", voucherId)          // ③ 设置 WHERE 条件
                .gt("stock", 0)                   // ④ 乐观锁 库存>0就可以扣减库存
                .update();                                    // ④ 真正执行 UPDATE 语句
        if (!success) {
            //扣减库存失败
            return Result.fail("库存不足");
        }
        //7 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1  订单ID
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //7.2  用户ID
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //7.3  代金券ID
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //8 返回订单ID
        return Result.ok(orderId);
    }
}
