package com.zhuanglide.micrboot.mvc;

import com.zhuanglide.micrboot.http.HttpContextRequest;
import com.zhuanglide.micrboot.http.HttpContextResponse;
import com.zhuanglide.micrboot.mvc.annotation.ApiCommand;
import com.zhuanglide.micrboot.mvc.annotation.ApiMethod;
import com.zhuanglide.micrboot.mvc.interceptor.ApiInterceptor;
import com.zhuanglide.micrboot.mvc.resolver.ApiMethodParamResolver;
import com.zhuanglide.micrboot.mvc.resolver.ExceptionResolver;
import com.zhuanglide.micrboot.mvc.resolver.ViewResolver;
import com.zhuanglide.micrboot.mvc.resolver.param.ApiMethodPathVariableResolver;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cglib.proxy.Proxy;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.util.*;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Created by wwj on 17/3/2.
 */
public class ApiDispatcher implements ApplicationContextAware,InitializingBean {
    private Map<String, Map<ApiMethod.RequestMethod, ApiMethodMapping>> commandMap;
    private Map<String, Map<ApiMethod.RequestMethod, ApiMethodMapping>> cachePathMap = new HashMap<String, Map<ApiMethod.RequestMethod, ApiMethodMapping>>();
    private AntPathMatcher matcher = new AntPathMatcher();
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    private static final String DEFAULT_STRATEGIES_PATH = "DefaultStrategies.properties";
    private static final Properties defaultStrategies;
    private ApplicationContext context;
    private List<ApiInterceptor> apiInterceptors;
    private List<ViewResolver> viewResolvers;
    private List<ApiMethodParamResolver> apiMethodParamResolvers;
    private List<ExceptionResolver> exceptionResolvers;
    private LocalVariableTableParameterNameDiscoverer discoverer = new LocalVariableTableParameterNameDiscoverer();
    static {
        try {// 加载默认配置
            ClassPathResource resource = new ClassPathResource(DEFAULT_STRATEGIES_PATH, ApiDispatcher.class);
            defaultStrategies = PropertiesLoaderUtils.loadProperties(resource);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load 'DefaultStrategies.properties': " + ex.getMessage());
        }
    }

    /**
     * 分发
     * @param request
     * @return
     */
    public ApiMethodMapping dispatcher(HttpContextRequest request) throws Exception{
        String methodValue = request.getHttpMethod();
        ApiMethod.RequestMethod requestMethod;
        //根据method 和 path分发请求
        if (ApiMethod.RequestMethod.GET.equals(methodValue)) {
            requestMethod = ApiMethod.RequestMethod.GET;
        } else if (ApiMethod.RequestMethod.POST.equals(methodValue)) {
            requestMethod = ApiMethod.RequestMethod.POST;
        } else if (ApiMethod.RequestMethod.UPDATE.equals(methodValue)) {
            requestMethod = ApiMethod.RequestMethod.UPDATE;
        } else if (ApiMethod.RequestMethod.DELETE.equals(methodValue)) {
            requestMethod = ApiMethod.RequestMethod.DELETE;
        }else{
            requestMethod = ApiMethod.RequestMethod.ALL;
        }

        return findApiMethodMapping(request.getRequestUrl(),requestMethod);
    }

    /**
     * core method
     * @param request
     * @param response
     * @throws Exception
     */
    public void doService(HttpContextRequest request, HttpContextResponse response) throws Exception {
        HandlerExecuteChain chain = new HandlerExecuteChain(apiInterceptors,exceptionResolvers);
        Throwable handlerEx = null;
        try {
            try {
                chain.setResult(doService0(request, response, chain));
            } catch (Exception e) {
                handlerEx = e;
            } catch (Throwable throwable) {
                handlerEx = throwable;
            }
            //gen view
            processDispatchResult(chain, request, response, handlerEx);
        } catch (Exception ex) {
            chain.triggerAfterCompletion(request, response, ex);
        }
    }


    public Object doService0(HttpContextRequest request, HttpContextResponse response, HandlerExecuteChain chain) throws Exception{
        return doProcess0(request, response, chain, true);
    }

    public Object doProcess(HttpContextRequest request, HttpContextResponse response) throws Exception{
        return doProcess0(request, response, null, true);
    }

    /**
     *
     * @param request
     * @param response
     * @param withInterceptor if true ,interceptors are usable,false -> disabled
     * @return
     * @throws Exception
     */
    public Object doProcess(HttpContextRequest request, HttpContextResponse response,boolean withInterceptor) throws Exception{
        return doProcess0(request, response, null, withInterceptor);
    }

    /**
     * do doProcess0 ,just
     * @param request
     * @param response
     * @param chain
     * @param withInterceptor if true ,interceptors are usable,false -> disabled
     * @return
     * @throws Exception
     */
    public Object doProcess0(HttpContextRequest request, HttpContextResponse response, HandlerExecuteChain chain, boolean withInterceptor) throws Exception{
        if(null == chain) {
            chain = new HandlerExecuteChain(apiInterceptors, exceptionResolvers);
        }

        ApiMethodMapping mapping;
        try {
            if(withInterceptor) {
                //pre dispatch ,you can do some to change the invoke method
                if (!chain.applyPreDispatch(request, response)) {
                    return null;
                }
            }
            //do dispatcher, url->method
            mapping = dispatcher(request);
            chain.setMapping(mapping); //set mapping
            if (null == mapping) {
                logger.error("can't find match for method={},path={}", request.getHttpMethod(), request.getRequestUrl());
                response.setStatus(HttpResponseStatus.NOT_FOUND);
                return null;
            }
            if(withInterceptor) {
                //post handler,you can change params
                chain.applyPostHandle(request, response);
            }
            //invoke & handler
            ApiMethodHandler handler = new ApiMethodHandler(mapping, request, response);
            return handler.invoke();
        } catch (Exception e) {
            throw e;
        } catch (Throwable cause) {
            throw new Exception(cause);
        }
    }


    private void processDispatchResult(HandlerExecuteChain chain, HttpContextRequest request, HttpContextResponse response,Throwable throwable) throws Exception {
        if (throwable == null) {
            // Did the handler return a view to render?
            if (chain.getResult() != null) {
                if (!render(chain.getResult(), request, response)) {
                    response.setStatus(HttpResponseStatus.NOT_FOUND);
                    logger.warn("no view found with path={}", chain.getMapping().getUrlPattern());
                }
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("no modelView returned to ApiDispatcher with path={},", request.getRequestUrl());
                }
            }
        }
        chain.triggerAfterCompletion(request, response, throwable);
    }

    protected boolean render(Object result, HttpContextRequest request, HttpContextResponse response) throws Exception {
        boolean resolver = false;
        for (ViewResolver viewResolver : this.viewResolvers) {
            ModelAndView mv = viewResolver.resolve(result);
            if (null != mv) {
                if (null != viewResolver.getContentType()) {
                    response.addHeader("Content-Type", viewResolver.getContentType());
                }
                viewResolver.render(mv, request, response);
                resolver = true;
                break;
            }
        }
        return resolver;
    }

    /**
     * init
     * @param context
     */
    protected void initStrategies(ApplicationContext context) {
        initApiInterceptor(context);
        initApiMethodParamResolvers(context);
        initViewResolver(context);
        initExceptionResolvers(context);
        Assert.isTrue(loadApiCommand(context));
    }

    /**
     * load from annotation
     * @param context
     * @return
     */
    private boolean loadApiCommand(ApplicationContext context) {
        if (context != null) {
            commandMap = new HashMap();
            Map<String, Object> objectMap = context.getBeansWithAnnotation(ApiCommand.class);
            for (Map.Entry<String, Object> entry : objectMap.entrySet()) {
                try {
                    Object bean = entry.getValue();
                    if (AopUtils.isAopProxy(bean)) {
                        bean = AopUtils.getTargetClass(bean);
                    }
                    Method[] methodArray = bean.getClass().getMethods();
                    ApiCommand apiCommand = bean.getClass().getAnnotation(ApiCommand.class);
                    String basePath = getBasePath(apiCommand.value());
                    for (Method method : methodArray) {
                        ApiMethod apiMethod = AnnotationUtils.findAnnotation(method, ApiMethod.class);
                        if (apiMethod != null) {
                            ApiMethodMapping apiCommandMapping = new ApiMethodMapping();
                            apiCommandMapping.setBean(entry.getValue());
                            apiCommandMapping.setProxyTargetBean(bean);
                            apiCommandMapping.setMethod(method);
                            apiCommandMapping.setUrlPattern(getFullUrlPattern(basePath, apiMethod.value()));
                            apiCommandMapping.setParamNames(discoverer.getParameterNames(method));
                            apiCommandMapping.setParamAnnotations(method.getParameterAnnotations());
                            apiCommandMapping.setParameterTypes(method.getParameterTypes());
                            Map<ApiMethod.RequestMethod, ApiMethodMapping> requestMethodMap = commandMap.get(apiCommandMapping.getUrlPattern());
                            if (requestMethodMap == null) {
                                requestMethodMap = new HashMap<ApiMethod.RequestMethod, ApiMethodMapping>();
                                commandMap.put(apiCommandMapping.getUrlPattern(), requestMethodMap);
                            }
                            requestMethodMap.put(apiMethod.method(), apiCommandMapping);
                        }
                    }

                } catch (Exception e) {
                    logger.error("init error", e);
                    return false;
                }
            }
        }
        return true;
    }

    public ApiMethodMapping findApiMethodMapping(String url, ApiMethod.RequestMethod requestMethod) {
        Map<ApiMethod.RequestMethod, ApiMethodMapping> map = cachePathMap.get(url);
        ApiMethodMapping apiMethodMapping = null;
        if (null == map) {
            synchronized (cachePathMap) {
                map = cachePathMap.get(url);
                if (null == map) {
                    for (Map.Entry<String, Map<ApiMethod.RequestMethod, ApiMethodMapping>> entry : commandMap.entrySet()) {
                        if(matcher.match(entry.getKey(), url)) {
                            map = entry.getValue();
                            if(checkCache()) {
                                cachePathMap.put(url, map);
                            }
                            break;
                        }
                    }
                }
            }
        }

        if(null != map){
            apiMethodMapping = map.get(requestMethod);
            if(null == apiMethodMapping && requestMethod != ApiMethod.RequestMethod.ALL) {
                apiMethodMapping = map.get(ApiMethod.RequestMethod.ALL);
            }
        }

        return apiMethodMapping;
    }
    private boolean checkCache(){
        return cachePathMap.size()<50000;
    }

    /**
     * get base Url
     * @param path
     * @return
     */
    private String getBasePath(String path){
        if(!path.startsWith("/")){
            path = "/" + path;
        }
        if (!path.endsWith("/")) {
            path += "/";
        }
        return path;
    }

    /**
     * get FullUrl
     * @param path
     * @param url
     * @return
     */
    private String getFullUrlPattern(String path, String url){
        StringBuffer _url = new StringBuffer(path);
        _url.append(url);
        while (_url.length()>1 && _url.charAt(1) == '/') {
            _url = _url.deleteCharAt(1);
        }
        while (_url.length()>0 && _url.charAt(_url.length()-1)=='/') {
            _url = _url.deleteCharAt(_url.length() - 1);
        }
        return _url.toString();
    }

    protected void initViewResolver(ApplicationContext context){
        Map<String, ViewResolver> matchingBeans =
                BeanFactoryUtils.beansOfTypeIncludingAncestors(context, ViewResolver.class, true, false);
        if (!matchingBeans.isEmpty()) {
            this.viewResolvers = new ArrayList<ViewResolver>(matchingBeans.values());
        }
        if (this.viewResolvers == null) {
            this.viewResolvers = getDefaultStrategies(context, ViewResolver.class);
            if (logger.isDebugEnabled()) {
                logger.debug("No ViewResolvers found ,using default");
            }
        }
        AnnotationAwareOrderComparator.sort(this.viewResolvers);
    }

    protected void initApiMethodParamResolvers(ApplicationContext context){
        Map<String, ApiMethodParamResolver> matchingBeans =
                BeanFactoryUtils.beansOfTypeIncludingAncestors(context, ApiMethodParamResolver.class, true, false);
        if (!matchingBeans.isEmpty()) {
            this.apiMethodParamResolvers = new ArrayList<ApiMethodParamResolver>(matchingBeans.values());
            AnnotationAwareOrderComparator.sort(this.viewResolvers);
        }

        if (this.apiMethodParamResolvers == null) {
            this.apiMethodParamResolvers = getDefaultStrategies(context, ApiMethodParamResolver.class);
            if (logger.isDebugEnabled()) {
                logger.debug("No ApiMethodParamResolver found ,using default");
            }
        }
    }

    protected void initExceptionResolvers(ApplicationContext context) {
        Map<String, ExceptionResolver> matchingBeans =
                BeanFactoryUtils.beansOfTypeIncludingAncestors(context, ExceptionResolver.class, true, false);
        if (!matchingBeans.isEmpty()) {
            this.exceptionResolvers = new ArrayList<ExceptionResolver>(matchingBeans.values());
            AnnotationAwareOrderComparator.sort(this.viewResolvers);
        }

        if (this.exceptionResolvers == null) {
            this.exceptionResolvers = getDefaultStrategies(context, ExceptionResolver.class);
            if (logger.isDebugEnabled()) {
                logger.debug("No ExceptionResolver found ,using default");
            }
        }
    }


    protected void initApiInterceptor(ApplicationContext context){
        Map<String, ApiInterceptor> matchingBeans =
                BeanFactoryUtils.beansOfTypeIncludingAncestors(context, ApiInterceptor.class, true, false);
        if (!matchingBeans.isEmpty()) {
            this.apiInterceptors = new ArrayList<ApiInterceptor>(matchingBeans.values());
            AnnotationAwareOrderComparator.sort(this.apiInterceptors);
        }
    }


    @Override
    public void afterPropertiesSet() throws Exception {
        initStrategies(context);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.context = applicationContext;
    }

    protected <T> T getDefaultStrategy(ApplicationContext context, Class<T> strategyInterface) {
        List<T> strategies = getDefaultStrategies(context, strategyInterface);
        if (strategies.size() != 1) {
            throw new BeanInitializationException(
                    "ApiDispatcher needs exactly 1 strategy for interface [" + strategyInterface.getName() + "]");
        }
        return strategies.get(0);
    }

    protected <T> List<T> getDefaultStrategies(ApplicationContext context, Class<T> strategyInterface) {
        String key = strategyInterface.getName();
        String value = defaultStrategies.getProperty(key);
        if (value != null) {
            String[] classNames = StringUtils.commaDelimitedListToStringArray(value);
            List<T> strategies = new ArrayList<T>(classNames.length);
            for (String className : classNames) {
                try {
                    Class<?> clazz = ClassUtils.forName(className, ApiDispatcher.class.getClassLoader());
                    Object strategy = createDefaultStrategy(context, clazz);
                    strategies.add((T) strategy);
                }
                catch (ClassNotFoundException ex) {
                    throw new BeanInitializationException(
                            "Could not find ApiDispatcher's default strategy class [" + className +
                                    "] for interface [" + key + "]", ex);
                }
                catch (LinkageError err) {
                    throw new BeanInitializationException(
                            "Error loading ApiDispatcher's default strategy class [" + className +
                                    "] for interface [" + key + "]: problem with class file or dependent class", err);
                }
            }
            return strategies;
        }
        else {
            return new LinkedList<T>();
        }
    }

    protected Object createDefaultStrategy(ApplicationContext context, Class<?> clazz) {
        return context.getAutowireCapableBeanFactory().createBean(clazz);
    }


    /**
     * invoke
     */
    public class ApiMethodHandler {
        private ApiMethodMapping mapping;
        private HttpContextRequest request;
        private HttpContextResponse response;

        public ApiMethodHandler(ApiMethodMapping mapping, HttpContextRequest request, HttpContextResponse response) {
            this.mapping = mapping;
            this.request = request;
            this.response = response;
        }

        public Object invoke() throws Exception {
            Method method = mapping.getMethod();
            Type[] parameterTypes = mapping.getParameterTypes();
            Annotation[][] annotations = mapping.getParamAnnotations();
            String[] paramNames = mapping.getParamNames();
            Object[] values = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                Type type = parameterTypes[i];
                Annotation[] paramAnnotations = annotations[i];
                String paramName = paramNames[i];
                ApiMethodParam apiMethodParam = new ApiMethodParam();
                apiMethodParam.setMethod(method);
                apiMethodParam.setParamAnnotations(paramAnnotations);
                apiMethodParam.setParamType(type);
                apiMethodParam.setParamName(paramName);
                Object paramObjectValue = null;

                boolean isResolver = false;
                if (!ObjectUtils.isEmpty(apiMethodParamResolvers)) {
                    for (ApiMethodParamResolver resolver : apiMethodParamResolvers) {
                        if (resolver.support(apiMethodParam)) {
                            isResolver = true;
                            //PathVariable need doPathVariableParse
                            if (resolver instanceof ApiMethodPathVariableResolver) {
                                ApiMethodPathVariableResolver pathVariableResolver = (ApiMethodPathVariableResolver) resolver;
                                pathVariableResolver.doPathVariableParse(matcher, mapping, request);
                            }
                            paramObjectValue = resolver.getParamObject(apiMethodParam, request, response);
                            break;
                        }
                    }
                }
                if (paramObjectValue == null) {
                    //set extend attribute
                    if (mapping.getExtendFields() != null) {
                        if (mapping.getExtendFields().containsKey(paramName)) {
                            paramObjectValue = mapping.getExtendFields().get(paramName);
                        }
                    }
                }

                if (isResolver && null == paramObjectValue) {
                    throw new IllegalArgumentException("can't resolver param=" + paramName + ",paramType=" + type);
                }
                values[i] = paramObjectValue;
            }

            Object bean = mapping.getBean();
            Object result;

            //invoke
            try {
                if (AopUtils.isAopProxy(bean)) {
                    if (AopUtils.isJdkDynamicProxy(bean)) {
                        result = Proxy.getInvocationHandler(bean).invoke(bean, method, values);
                    } else { //cglib
                        result = method.invoke(bean, values);
                    }
                } else {
                    result = ReflectionUtils.invokeMethod(method, bean, values);
                }
                return result;
            } catch (Throwable throwable) {
                StringBuffer val = new StringBuffer();
                for (int i = 0; i < parameterTypes.length; i++) {
                    val.append(parameterTypes).append(":").append(values[i]).append(";");
                }
                logger.error("invoke exception,object=" + bean + ",method=" + method + ",values" + val.toString(), throwable);
                throw new Exception("invoke exception,url=" + request.getRequestUrl());
            }
        }
    }
}
