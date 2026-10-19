package com.demo.storage.tcc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本服务的 @TccAction 动作登记表（启动时扫描一次）
 *
 * 每个动作持有：Spring bean、注解元数据、三个阶段方法的 Method 引用与形参名。
 * TccInvokeDispatcher 按 phase 从这里取 Method 反射调用；
 * TccActionRegistrar 从这里取注册报文推送到注册服务。
 */
@Component
public class LocalTccActionRegistry {

    private static final Logger log = LoggerFactory.getLogger(LocalTccActionRegistry.class);

    /** 一个已登记的动作 */
    public static class ActionEntry {
        private final Object bean;
        private final TccAction annotation;
        private final Map<String, Method> phaseMethods;   // TRY/CONFIRM/CANCEL -> Method

        ActionEntry(Object bean, TccAction annotation, Map<String, Method> phaseMethods) {
            this.bean = bean;
            this.annotation = annotation;
            this.phaseMethods = phaseMethods;
        }

        public Object getBean() {
            return bean;
        }

        public TccAction getAnnotation() {
            return annotation;
        }

        public Method methodOf(String phase) {
            return phaseMethods.get(phase);
        }
    }

    private final ApplicationContext applicationContext;
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    private volatile Map<String, ActionEntry> actions;

    public LocalTccActionRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /** 扫描本服务所有 @TccAction bean（懒加载、只扫一次） */
    private Map<String, ActionEntry> actions() {
        Map<String, ActionEntry> result = actions;
        if (result == null) {
            synchronized (this) {
                result = actions;
                if (result == null) {
                    result = scan();
                    actions = result;
                }
            }
        }
        return result;
    }

    private Map<String, ActionEntry> scan() {
        Map<String, ActionEntry> result = new LinkedHashMap<>();
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(TccAction.class);
        for (Object bean : beans.values()) {
            // 取用户类（绕开 CGLIB 代理子类），注解与方法都在用户类上解析
            Class<?> targetClass = ClassUtils.getUserClass(bean);
            TccAction ann = targetClass.getAnnotation(TccAction.class);
            if (ann == null) {
                continue;
            }
            Map<String, Method> phaseMethods = new LinkedHashMap<>();
            phaseMethods.put("TRY", findMethod(targetClass, ann.tryMethod()));
            phaseMethods.put("CONFIRM", findMethod(targetClass, ann.confirmMethod()));
            phaseMethods.put("CANCEL", findMethod(targetClass, ann.cancelMethod()));
            for (Map.Entry<String, Method> e : phaseMethods.entrySet()) {
                if (e.getValue() == null) {
                    throw new IllegalStateException(
                            "@TccAction(name=" + ann.name() + ") 声明的 " + e.getKey()
                                    + " 方法在本类中不存在，检查注解配置");
                }
                // 触发一次形参名解析：若编译期丢了参数名，这里快速失败而不是运行期莫名绑定失败
                if (parameterNameDiscoverer.getParameterNames(e.getValue()) == null) {
                    throw new IllegalStateException(
                            "@TccAction(name=" + ann.name() + ") 的方法 " + e.getValue().getName()
                                    + " 无法解析形参名（需以 -parameters 编译或携带调试信息）");
                }
            }
            result.put(ann.name(), new ActionEntry(bean, ann, phaseMethods));
            log.info("[TccAction] 登记本服务动作：{}（try={}, confirm={}, cancel={}）",
                    ann.name(), ann.tryMethod(), ann.confirmMethod(), ann.cancelMethod());
        }
        return Collections.unmodifiableMap(result);
    }

    private Method findMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /** 按动作名查找；找不到返回 null */
    public ActionEntry find(String actionName) {
        return actions().get(actionName);
    }

    /** 全部动作（注册器用） */
    public Collection<ActionEntry> all() {
        return actions().values();
    }

    /** 形参名解析（分发器绑定参数时用） */
    public String[] parameterNamesOf(Method method) {
        return parameterNameDiscoverer.getParameterNames(method);
    }
}
