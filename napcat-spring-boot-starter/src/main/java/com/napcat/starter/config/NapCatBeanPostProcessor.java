package com.napcat.starter.config;

import com.napcat.core.handler.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

@Slf4j
@RequiredArgsConstructor
public class NapCatBeanPostProcessor implements BeanPostProcessor, Ordered {

    private final HandlerRegistry registry;
    private final ApplicationContext ctx;

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // 绕过 Spring CGLIB 代理，获取原始类的方法
        Class<?> targetClass = ClassUtils.getUserClass(bean);
        registry.registerBean(bean, targetClass);

        // 注册接口 handler
        if (bean instanceof EventHandler<?> eh) {
            registry.registerEventHandler(eh);
        }
        if (bean instanceof CommandHandler ch) {
            registry.registerCommandHandler(ch);
        }
        if (bean instanceof BotInitializer bi) {
            registry.registerInitializer(bi);
        }

        return bean;
    }
}
