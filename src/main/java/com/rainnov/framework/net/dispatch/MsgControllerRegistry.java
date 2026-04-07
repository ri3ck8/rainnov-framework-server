package com.rainnov.framework.net.dispatch;

import com.rainnov.framework.net.queue.GroupType;
import com.rainnov.framework.net.session.GameSession;

import com.google.protobuf.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息号 → MethodInvoker 映射注册表。
 * 在 Spring 启动时扫描所有 @MsgController Bean，建立映射。
 */
@Slf4j
@Component
public class MsgControllerRegistry implements ApplicationContextAware, InitializingBean {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(MsgController.class);
        for (Object bean : beans.values()) {
            for (Method method : bean.getClass().getDeclaredMethods()) {
                MsgMapping mapping = method.getAnnotation(MsgMapping.class);
                if (mapping == null) continue;

                int msgId = mapping.value();
                Parameter[] params = method.getParameters();

                // 6.3: 方法签名校验 — 第一个参数必须是 GameSession
                if (params.length != 2 || !GameSession.class.isAssignableFrom(params[0].getType())) {
                    throw new IllegalStateException(
                            method + " 的第一个参数必须是 GameSession");
                }

                // 6.3: 方法签名校验 — 第二个参数必须是 Message 子类
                if (!Message.class.isAssignableFrom(params[1].getType())) {
                    throw new IllegalStateException(
                            method + " 的第二个参数必须是 com.google.protobuf.Message 的子类");
                }

                // 6.4: msgId 重复检测
                if (invokerMap.containsKey(msgId)) {
                    throw new IllegalStateException("msgId=" + msgId + " 存在重复注册");
                }

                // 6.5: @MsgMapping value 与参数类名前缀 C{msgId}_ 一致性校验
                String paramClassName = params[1].getType().getSimpleName();
                if (paramClassName.matches("C\\d+_.*")) {
                    int inferredMsgId = Integer.parseInt(
                            paramClassName.substring(1, paramClassName.indexOf('_')));
                    if (inferredMsgId != msgId) {
                        throw new IllegalStateException(
                                method + ": @MsgMapping(" + msgId + ") 与参数类名 " + paramClassName +
                                " 推导出的 msgId=" + inferredMsgId + " 不一致");
                    }
                }

                @SuppressWarnings("unchecked")
                Class<? extends Message> payloadType = (Class<? extends Message>) params[1].getType();
                invokerMap.put(msgId, new MethodInvoker(bean, method, payloadType, mapping.groupBy(), mapping.requireAuth()));
                log.info("注册 MsgMapping: msgId={} -> {}.{}()",
                        msgId, bean.getClass().getSimpleName(), method.getName());
            }
        }
    }

    /**
     * 封装反射调用所需的全部信息。
     */
    public record MethodInvoker(
            Object bean,
            Method method,
            Class<? extends Message> payloadType,
            GroupType groupType,
            boolean requireAuth
    ) {}

    private final ConcurrentHashMap<Integer, MethodInvoker> invokerMap = new ConcurrentHashMap<>();

    /**
     * 根据 msgId 查找 MethodInvoker（找不到返回 null）。
     */
    public MethodInvoker find(int msgId) {
        return invokerMap.get(msgId);
    }

    /**
     * 获取所有已注册的 msgId（用于调试）。
     */
    public Set<Integer> registeredMsgIds() {
        return invokerMap.keySet();
    }
}
