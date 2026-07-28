package com.rainnov.framework.net.dispatch;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在方法上，声明该方法处理的消息号和鉴权要求。
 * 消息统一按用户维度串行消费（每个 Session 一个队列 + 专属虚拟线程）；
 * 跨用户的共享状态（队伍、公会等）由业务侧用锁或分布式锁显式控制。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MsgMapping {

    /** 该方法处理的消息号 */
    int value();

    /** 是否需要登录才能访问，默认需要鉴权；登录接口等公开接口设为 false */
    boolean requireAuth() default true;
}
