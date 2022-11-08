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
package org.apache.dubbo.config.spring;

import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.spring.context.DubboConfigBeanInitializer;
import org.apache.dubbo.config.spring.reference.ReferenceBeanManager;
import org.apache.dubbo.config.spring.reference.ReferenceBeanSupport;
import org.apache.dubbo.config.spring.reference.ReferenceAttributes;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.rpc.proxy.AbstractProxyFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.target.AbstractLazyCreationTargetSource;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * <p>
 * Spring FactoryBean for {@link ReferenceConfig}.
 * </p>
 *
 *
 * <p></p>
 * Step 1: Register ReferenceBean in Java-config class:
 * <pre class="code">
 * &#64;Configuration
 * public class ReferenceConfiguration {
 *     &#64;Bean
 *     &#64;DubboReference(group = "demo")
 *     public ReferenceBean&lt;HelloService&gt; helloService() {
 *         return new ReferenceBean();
 *     }
 *
 *     // As GenericService
 *     &#64;Bean
 *     &#64;DubboReference(group = "demo", interfaceClass = HelloService.class)
 *     public ReferenceBean&lt;GenericService&gt; genericHelloService() {
 *         return new ReferenceBean();
 *     }
 * }
 * </pre>
 *
 * Or register ReferenceBean in xml:
 * <pre class="code">
 * &lt;dubbo:reference id="helloService" interface="org.apache.dubbo.config.spring.api.HelloService"/&gt;
 * &lt;!-- As GenericService --&gt;
 * &lt;dubbo:reference id="genericHelloService" interface="org.apache.dubbo.config.spring.api.HelloService" generic="true"/&gt;
 * </pre>
 *
 * Step 2: Inject ReferenceBean by @Autowired
 * <pre class="code">
 * public class FooController {
 *     &#64;Autowired
 *     private HelloService helloService;
 *
 *     &#64;Autowired
 *     private GenericService genericHelloService;
 * }
 * </pre>
 *
 *
 * @see org.apache.dubbo.config.annotation.DubboReference
 * @see org.apache.dubbo.config.spring.reference.ReferenceBeanBuilder
 */
public class ReferenceBean<T> implements FactoryBean,
        ApplicationContextAware, BeanClassLoaderAware, BeanNameAware, InitializingBean, DisposableBean {

    /**
     *  ReferenceBean继承自Spring的FactoryBean，而FactoryBean常用于实例化那些配置复杂、或者无法通过反射生成实例的类，
     *  进而完成Spring Bean的注册，与`@Configration`和`@Bean`的功能类似。
     *
     *  ReferenceBean代表的是对Dubbo远程服务的引用，每个XML配置<dubbo:reference id="helloService ...">，或者等效的@DubboReference注解，
     *  都会生成一个ReferenceBean，将远程服务当做本地服务那样，注册到Spring当中
     *
    */


    private transient ApplicationContext applicationContext;

    private ClassLoader beanClassLoader;

    // lazy proxy of reference
    private Object lazyProxy;

    // beanName
    protected String id;

    // reference key
    private String key;

    /**
     * The interface class of the reference service
     */
    private Class<?> interfaceClass;

    /*
     * remote service interface class name
     */
    // 'interfaceName' field for compatible with seata-1.4.0: io.seata.rm.tcc.remoting.parser.DubboRemotingParser#getServiceDesc()
    private String interfaceName;

    //from annotation attributes
    private Map<String, Object> referenceProps;

    //from xml bean definition
    private MutablePropertyValues propertyValues;

    //actual reference config
    private ReferenceConfig referenceConfig;

    // Registration sources of this reference, may be xml file or annotation location
    private List<Map<String,Object>> sources = new ArrayList<>();

    public ReferenceBean() {
        super();
    }

    public ReferenceBean(Map<String, Object> referenceProps) {
        this.referenceProps = referenceProps;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.beanClassLoader = classLoader;
    }

    @Override
    public void setBeanName(String name) {
        this.setId(name);
    }

    /**
     * Create bean instance.
     * <p/>
     * When Spring searches beans by type, if Spring cannot determine the type of a factory bean, it may try to initialize it.
     * The ReferenceBean is also a FactoryBean.
     *
     * <p/>
     * In addition, if some ReferenceBeans are dependent on beans that are initialized very early,
     * and dubbo config beans are not ready yet, there will be many unexpected problems if initializing the dubbo reference immediately.
     *
     * <p/>
     * When it is initialized, only a lazy proxy object will be created,
     * and dubbo reference-related resources will not be initialized.
     * <br/>
     * In this way, the influence of Spring is eliminated, and the dubbo configuration initialization is controllable.
     *
     * <p/>
     * Dubbo config beans are initialized in DubboConfigBeanInitializer.
     * <br/>
     * The actual references will be processing in DubboBootstrap.referServices().
     *
     * @see DubboConfigBeanInitializer
     * @see org.apache.dubbo.config.bootstrap.DubboBootstrap
     */

    /**
     * 当Spring通过类型搜索Bean时，如果Spring无法确定一个 factory bean 的类型时，它可能会尝试对其进行初始化。而 ReferenceBean 也是一个
     * FactoryBean。（但是该问题在Dubo中已经通过装饰BeanDefinition解决了）另外，如果一些 ReferenceBeans 依赖很早初始化的bean， 而 dubbo config benas
     * 还没有准备好，如果马上初始化 dubbo reference，可能会出现一些意想不到的问题。所以，在Dubbo3.0中，referencebean 初始化时，只会创建一个 lazy proxy，与dubbo
     * 引用相关的资源不会被初始化。这样就排除了Spring的影响，dubbo配置初始化是可控的了。
     *
     * 当Spring启动过程中，发现该Bean被其他Bean依赖，则会通过getObject方法中进行懒加载，创建一个lazy Proxy，真正的初始化当首次调用代理时才会触发
    */
    @Override
    public Object getObject() {
        if (lazyProxy == null) {
            createLazyProxy();
        }
        return lazyProxy;
    }

    @Override
    public Class<?> getObjectType() {
        return getInterfaceClass();
    }

    @Override
    @Parameter(excluded = true)
    public boolean isSingleton() {
        return true;
    }

    @Override
    public void afterPropertiesSet() throws Exception {

        /**
         * 可以发现，Dubbo并不会在该方法中进行初始化，只是对 BeanDefinition 解析了 interfaceClass 和 interfaceName 等属性值，
         * 然后将Bean收集到 ReferenceBeanManager中。
         *
        */

        ConfigurableListableBeanFactory beanFactory = getBeanFactory();

        // pre init xml reference bean or @DubboReference annotation
        Assert.notEmptyString(getId(), "The id of ReferenceBean cannot be empty");
        BeanDefinition beanDefinition = beanFactory.getBeanDefinition(getId());
        this.interfaceClass = (Class<?>) beanDefinition.getAttribute(ReferenceAttributes.INTERFACE_CLASS);
        this.interfaceName = (String) beanDefinition.getAttribute(ReferenceAttributes.INTERFACE_NAME);
        Assert.notNull(this.interfaceClass, "The interface class of ReferenceBean is not initialized");

        if (beanDefinition.hasAttribute(Constants.REFERENCE_PROPS)) {
            // @DubboReference annotation at java-config class @Bean method
            // @DubboReference annotation at reference field or setter method
            referenceProps = (Map<String, Object>) beanDefinition.getAttribute(Constants.REFERENCE_PROPS);
        } else {
            if (beanDefinition instanceof AnnotatedBeanDefinition) {
                // Return ReferenceBean in java-config class @Bean method
                if (referenceProps == null) {
                    referenceProps = new LinkedHashMap<>();
                }
                ReferenceBeanSupport.convertReferenceProps(referenceProps, interfaceClass);
                if (this.interfaceName == null) {
                    this.interfaceName = (String) referenceProps.get(ReferenceAttributes.INTERFACE);
                }
            } else {
                // xml reference bean
                propertyValues = beanDefinition.getPropertyValues();
            }
        }
        Assert.notNull(this.interfaceName, "The interface name of ReferenceBean is not initialized");

//        this.sources = (List<Map<String, Object>>) beanDefinition.getAttribute(Constants.REFERENCE_SOURCES);
//        Assert.notNull(this.sources, "The registration sources of this reference is empty");

        ReferenceBeanManager referenceBeanManager = beanFactory.getBean(ReferenceBeanManager.BEAN_NAME, ReferenceBeanManager.class);
        referenceBeanManager.addReference(this);
    }

    private ConfigurableListableBeanFactory getBeanFactory() {
        return (ConfigurableListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
    }

    @Override
    public void destroy() {
        // do nothing
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }


    /**
     * The interface of this ReferenceBean, for injection purpose
     * @return
     */
    public Class<?> getInterfaceClass() {
        // Compatible with seata-1.4.0: io.seata.rm.tcc.remoting.parser.DubboRemotingParser#getServiceDesc()
        return interfaceClass;
    }

    /**
     * The interface of remote service
     */
    public String getServiceInterface() {
        return interfaceName;
    }

    /**
     * The group of the service
     */
    public String getGroup() {
        // Compatible with seata-1.4.0: io.seata.rm.tcc.remoting.parser.DubboRemotingParser#getServiceDesc()
        return referenceConfig.getGroup();
    }

    /**
     * The version of the service
     */
    public String getVersion() {
        // Compatible with seata-1.4.0: io.seata.rm.tcc.remoting.parser.DubboRemotingParser#getServiceDesc()
        return referenceConfig.getVersion();
    }

    public String getKey() {
        return key;
    }

    public Map<String, Object> getReferenceProps() {
        return referenceProps;
    }

    public MutablePropertyValues getPropertyValues() {
        return propertyValues;
    }

    public ReferenceConfig getReferenceConfig() {
        return referenceConfig;
    }

    public void setKeyAndReferenceConfig(String key, ReferenceConfig referenceConfig) {
        this.key = key;
        this.referenceConfig = referenceConfig;
    }

    /**
     * Create lazy proxy for reference.
     */
    private void createLazyProxy() {

        //set proxy interfaces
        //see also: org.apache.dubbo.rpc.proxy.AbstractProxyFactory.getProxy(org.apache.dubbo.rpc.Invoker<T>, boolean)

        //基于Spring的工具类，生成懒加载的代理
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setTargetSource(new DubboReferenceLazyInitTargetSource());
        proxyFactory.addInterface(interfaceClass);
        Class<?>[] internalInterfaces = AbstractProxyFactory.getInternalInterfaces();
        for (Class<?> anInterface : internalInterfaces) {
            proxyFactory.addInterface(anInterface);
        }
        if (!StringUtils.isEquals(interfaceClass.getName(), interfaceName)) {
            //add service interface
            try {
                Class<?> serviceInterface = ClassUtils.forName(interfaceName, beanClassLoader);
                proxyFactory.addInterface(serviceInterface);
            } catch (ClassNotFoundException e) {
                // generic call maybe without service interface class locally
            }
        }

        this.lazyProxy = proxyFactory.getProxy(this.beanClassLoader);
    }

    private Object getCallProxy() throws Exception {
        if (referenceConfig == null) {
            throw new IllegalStateException("ReferenceBean is not ready yet, please make sure to call reference interface method after dubbo is started.");
        }

        //通过ReferenceConfig触发真正的初始化，ReferenceConfig由ReferenceBeanManager管理并赋值
        return referenceConfig.get();
    }

    private class DubboReferenceLazyInitTargetSource extends AbstractLazyCreationTargetSource {

        //抽象类要求子类调用这个方法来返回延迟初始化的对象，第一次调用代理时调用该方法。
        //当需要将某个依赖项的引用传递给对象，但不希望在首次使用该依赖项之前创建该依赖项时，这很有用。
        // 一个典型的场景是连接到远程资源。这正好解决上述Dubbo3.0中的诉求。
        @Override
        protected Object createObject() throws Exception {
            return getCallProxy();
        }

        @Override
        public synchronized Class<?> getTargetClass() {
            return getInterfaceClass();
        }
    }

}
