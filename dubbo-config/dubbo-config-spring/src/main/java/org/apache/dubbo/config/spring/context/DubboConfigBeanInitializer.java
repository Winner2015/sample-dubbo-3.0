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
package org.apache.dubbo.config.spring.context;

import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.MetadataReportConfig;
import org.apache.dubbo.config.MetricsConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.SslConfig;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.spring.ConfigCenterBean;
import org.apache.dubbo.config.spring.reference.ReferenceBeanManager;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.util.concurrent.atomic.AtomicBoolean;


/**
 *
 * Dubbo config bean initializer.
 *
 * NOTE: Dubbo config beans MUST be initialized after registering all BeanPostProcessors,
 * that is after the AbstractApplicationContext#registerBeanPostProcessors() method.
 *
 * ?????????????????????????????????
 * 1???????????????Dubbo?????????Bean??????????????????????????? configManager????????????ApplicationConfig???RegistryConfig???ProviderConfig???ConsumerConfig??????
 * 2?????????ReferenceBean???????????????referenceBeanManager.prepareReferenceBeans
 */
public class DubboConfigBeanInitializer implements BeanFactoryAware, InitializingBean {

    public static String BEAN_NAME = "dubboConfigBeanInitializer";

    private AtomicBoolean initialized = new AtomicBoolean(false);
    private ConfigurableListableBeanFactory beanFactory;
    private ReferenceBeanManager referenceBeanManager;
    private ConfigManager configManager;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        init();
    }

    private void init() {
        if (initialized.compareAndSet(false, true)) {
            configManager = ApplicationModel.getConfigManager();
            referenceBeanManager = beanFactory.getBean(ReferenceBeanManager.BEAN_NAME, ReferenceBeanManager.class);
            try {
                prepareDubboConfigBeans();
                referenceBeanManager.prepareReferenceBeans();
            } catch (Throwable e) {
                throw new FatalBeanException("Initialization dubbo config beans failed", e);
            }
        }
    }

    /**
     * Initializes there Dubbo's Config Beans before @Reference bean autowiring
     *
     * ??????@DubboReference?????????bean????????????????????????Dubbo?????????Bean??????????????????????????? configManager
     * ????????????ApplicationConfig???RegistryConfig???ProviderConfig???ConsumerConfig?????????
     */
    private void prepareDubboConfigBeans() {
        //Make sure all these config beans are inited and registered to ConfigManager
        loadConfigBeansOfType(ApplicationConfig.class);
        loadConfigBeansOfType(ModuleConfig.class);
        loadConfigBeansOfType(RegistryConfig.class);
        loadConfigBeansOfType(ProtocolConfig.class);
        loadConfigBeansOfType(MonitorConfig.class);
        loadConfigBeansOfType(ProviderConfig.class);
        loadConfigBeansOfType(ConsumerConfig.class);
        loadConfigBeansOfType(ConfigCenterBean.class);
        loadConfigBeansOfType(MetadataReportConfig.class);
        loadConfigBeansOfType(MetricsConfig.class);
        loadConfigBeansOfType(SslConfig.class);
    }

    private void loadConfigBeansOfType(Class<? extends AbstractConfig> configClass) {
        String[] beanNames = beanFactory.getBeanNamesForType(configClass, true, false);
        for (String beanName : beanNames) {
            AbstractConfig configBean = beanFactory.getBean(beanName, configClass);
            // Register config bean here, avoid relying on unreliable @PostConstruct init method
            configManager.addConfig(configBean);
        }
    }

}
