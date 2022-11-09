/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config.spring.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.dubbo.config.spring.beans.factory.annotation.ServicePackagesHolder;
import org.apache.dubbo.config.spring.context.DubboConfigBeanInitializer;
import org.apache.dubbo.config.spring.reference.ReferenceBeanManager;
import org.apache.dubbo.config.spring.beans.factory.annotation.DubboConfigAliasPostProcessor;
import org.apache.dubbo.config.spring.beans.factory.annotation.ReferenceAnnotationBeanPostProcessor;
import org.apache.dubbo.config.spring.beans.factory.config.DubboConfigDefaultPropertyValueBeanPostProcessor;
import org.apache.dubbo.config.spring.context.DubboBootstrapApplicationListener;
import org.apache.dubbo.config.spring.context.DubboInfraBeanRegisterPostProcessor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import java.util.HashMap;
import java.util.Map;

/**
 * Dubbo Bean utilities class
 *
 * @since 2.7.6
 */
public interface DubboBeanUtils {

    static final Log log = LogFactory.getLog(DubboBeanUtils.class);

    /**
     * Register the common beans
     *
     * @param registry {@link BeanDefinitionRegistry}
     * @see ReferenceAnnotationBeanPostProcessor
     * @see DubboConfigDefaultPropertyValueBeanPostProcessor
     * @see DubboConfigAliasPostProcessor
     * @see DubboBootstrapApplicationListener
     */
    static void registerCommonBeans(BeanDefinitionRegistry registry) {

        //ReferenceAnnotationBeanPostProcessor扫描到被@DubboService注解的接口会放进ServicePackagesHolder，以便后续作为容器类使用
        registerInfrastructureBean(registry, ServicePackagesHolder.BEAN_NAME, ServicePackagesHolder.class);

        //Dubbo消费者中，被<dubbo:reference ...>配置的接口，会被放入ReferenceBeanManager，以便后续作为容器类使用
        registerInfrastructureBean(registry, ReferenceBeanManager.BEAN_NAME, ReferenceBeanManager.class);

        //注册后置处理器，扫描出@DubboReference类，并注入到被依赖的其他Bean当中
        registerInfrastructureBean(registry, ReferenceAnnotationBeanPostProcessor.BEAN_NAME,
                ReferenceAnnotationBeanPostProcessor.class);

        // 注册后置处理器，为Dubbo配置类注册别名
        registerInfrastructureBean(registry, DubboConfigAliasPostProcessor.BEAN_NAME,
                DubboConfigAliasPostProcessor.class);

        // 监听Spring容器的启动与关闭，触发Dubbo的初始化与销毁
        registerInfrastructureBean(registry, DubboBootstrapApplicationListener.BEAN_NAME,
                DubboBootstrapApplicationListener.class);

        // 注册后置处理器，为Dubbo的配置类注入默认值（比如ID）
        registerInfrastructureBean(registry, DubboConfigDefaultPropertyValueBeanPostProcessor.BEAN_NAME,
                DubboConfigDefaultPropertyValueBeanPostProcessor.class);

        // 用于初始化Dubbo的配置类，比如ApplicationConfig、ProviderConfig、ConsumerConfig等，以及触发ReferenceBean的初始化
        registerInfrastructureBean(registry, DubboConfigBeanInitializer.BEAN_NAME, DubboConfigBeanInitializer.class);

        // 注册一些其他Dubbo依赖的基础bean
        registerInfrastructureBean(registry, DubboInfraBeanRegisterPostProcessor.BEAN_NAME, DubboInfraBeanRegisterPostProcessor.class);
    }

    /**
     * Register Infrastructure Bean
     *
     * @param beanDefinitionRegistry {@link BeanDefinitionRegistry}
     * @param beanType               the type of bean
     * @param beanName               the name of bean
     * @return if it's a first time to register, return <code>true</code>, or <code>false</code>
     */
    static boolean registerInfrastructureBean(BeanDefinitionRegistry beanDefinitionRegistry,
                                                     String beanName,
                                                     Class<?> beanType) {

        boolean registered = false;

        if (!beanDefinitionRegistry.containsBeanDefinition(beanName)) {
            RootBeanDefinition beanDefinition = new RootBeanDefinition(beanType);
            beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            beanDefinitionRegistry.registerBeanDefinition(beanName, beanDefinition);
            registered = true;

            if (log.isDebugEnabled()) {
                log.debug("The Infrastructure bean definition [" + beanDefinition
                        + "with name [" + beanName + "] has been registered.");
            }
        }

        return registered;
    }

    /**
     * Register some beans later
     * Call this method in BeanDefinitionRegistryPostProcessor,
     * in order to enable the registered BeanFactoryPostProcessor bean to be loaded and executed.
     * @see DubboInfraBeanRegisterPostProcessor
     * @param beanFactory
     * @param registry
     */
    static void registerBeansIfNotExists(ConfigurableListableBeanFactory beanFactory, BeanDefinitionRegistry registry) {
        // Auto register a PropertyPlaceholderConfigurer bean to resolve placeholders with Spring Environment PropertySources
        // when loading dubbo xml config with @ImportResource
        if (!checkBeanExists(beanFactory, PropertySourcesPlaceholderConfigurer.class)) {
            Map<String, Object> propertySourcesPlaceholderPropertyValues = new HashMap<>();
            propertySourcesPlaceholderPropertyValues.put("ignoreUnresolvablePlaceholders", true);

            registerBeanDefinition(registry, PropertySourcesPlaceholderConfigurer.class.getName(),
                    PropertySourcesPlaceholderConfigurer.class, propertySourcesPlaceholderPropertyValues);
        }
    }

    static boolean registerBeanDefinition(BeanDefinitionRegistry registry, String beanName,
                                                     Class<?> beanClass, Map<String, Object> extraPropertyValues) {
        if (registry.containsBeanDefinition(beanName)) {
            return false;
        }

        BeanDefinition beanDefinition = BeanDefinitionBuilder.genericBeanDefinition(beanClass).getBeanDefinition();
        if (extraPropertyValues != null) {
            for (Map.Entry<String, Object> entry : extraPropertyValues.entrySet()) {
                beanDefinition.getPropertyValues().add(entry.getKey(), entry.getValue());
            }
        }

        registry.registerBeanDefinition(beanName, beanDefinition);
        return true;
    }

    static boolean checkBeanExists(ConfigurableListableBeanFactory beanFactory, Class<?> targetClass) {
        String[] beanNames = beanFactory.getBeanNamesForType(targetClass, true, false);
        return (beanNames != null && beanNames.length > 0);
    }


    static ReferenceAnnotationBeanPostProcessor getReferenceAnnotationBeanPostProcessor(AbstractBeanFactory beanFactory) {
        for (BeanPostProcessor beanPostProcessor : beanFactory.getBeanPostProcessors()) {
            if (beanPostProcessor instanceof ReferenceAnnotationBeanPostProcessor) {
                return (ReferenceAnnotationBeanPostProcessor) beanPostProcessor;
            }
        }
        return null;
    }

    static ReferenceAnnotationBeanPostProcessor getReferenceAnnotationBeanPostProcessor(ApplicationContext applicationContext) {
        return getReferenceAnnotationBeanPostProcessor((AbstractBeanFactory) applicationContext.getAutowireCapableBeanFactory());
    }
}
