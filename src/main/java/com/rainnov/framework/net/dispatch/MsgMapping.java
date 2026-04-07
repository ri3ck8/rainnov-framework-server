package com.rainnov.framework.net.dispatch;

import com.rainnov.framework.net.queue.GroupType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在方法上，声明该方法处理的消息号、串行维度和鉴权要求。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MsgMapping {

    /** 该方法处理的消息号 */
    int value();

    /** 串行消费维度，默认按用户队列串行 */
    GroupType groupBy() default GroupType.USER;

    /** 是否需要登录才能访问，默认需要鉴权；登录接口等公开接口设为 false */
    boolean requireAuth() default true;
}
