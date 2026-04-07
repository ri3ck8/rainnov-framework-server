package com.rainnov.framework.net.dispatch;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记在类上，表示该类为消息处理模块（类似 Spring 的 @Controller）。
 * 同时注册为 Spring Bean。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface MsgController {
}
