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
 * 本服务的 @TccAction 动作登记表（启动时扫描一次，等价 Seata 的 TCCResource 注册）
 *
 * 扫描所有 bean 的方法级 @TccAction（只解析 bean 类型、不实例化，命中动作才取 bean 实例），
 * 按命名约定解析动作名与伴生的 Confirm/Cancel 方法，
 * 反射定位 Method 对象后缓存——运行期不再做任何名字解析。
 * 伴生方法缺失/形参名不可解析都在这里启动期快速失败。
 *
 * TccInvokeDispatcher 二阶段从这里取 Method 反射调用；
 * TccActionRegistrar 从这里取派生结果推送到注册服务。
 */
@Component
public class LocalTccActionRegistry {

    private static final Logger log = LoggerFactory.getLogger(LocalTccActionRegistry.class);

    /** 一个已登记的动作：bean + 三个阶段方法的 Method 缓存 */
    public static class ActionEntry {
        private final Object bean;
        private final String actionName;
        private final Method tryMethod;
        private final Method confirmMethod;
        private final Method cancelMethod;

        ActionEntry(Object bean, String actionName,
                    Method tryMethod, Method confirmMethod, Method cancelMethod) {
            this.bean = bean;
            this.actionName = actionName;
            this.tryMethod = tryMethod;
            this.confirmMethod = confirmMethod;
            this.cancelMethod = cancelMethod;
        }

        public Object getBean() {
            return bean;
        }

        public String getActionName() {
            return actionName;
        }

        public Method getTryMethod() {
            return tryMethod;
        }

        public Method getConfirmMethod() {
            return confirmMethod;
        }

        public Method getCancelMethod() {
            return cancelMethod;
        }

        /** 二阶段分发用：TRY/CONFIRM/CANCEL -> Method */
        public Method methodOf(String phase) {
            switch (phase == null ? "" : phase) {
                case "TRY":     return tryMethod;
                case "CONFIRM": return confirmMethod;
                case "CANCEL":  return cancelMethod;
                default:        return null;
            }
        }
    }

    private final ApplicationContext applicationContext;
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    private volatile Map<String, ActionEntry> actions;

    public LocalTccActionRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /** 懒加载、只扫一次 */
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
        // 注解在方法上：逐 bean 扫方法。getType 只从 BeanDefinition 元数据解析类型，
        // 不实例化任何 bean（@Lazy 不被触发、不产生额外对象）；命中动作才 getBean 取实例。
        // 与 Spring 自身的方法级注解扫描（@EventListener 等）同一套路
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = applicationContext.getType(beanName);
            } catch (Exception e) {
                continue;   // 工厂 bean 等解析不出类型的跳过
            }
            if (type == null) {
                continue;
            }
            // 取用户类，绕开 CGLIB 代理子类
            Class<?> targetClass = ClassUtils.getUserClass(type);
            for (Method method : targetClass.getDeclaredMethods()) {
                TccAction ann = method.getAnnotation(TccAction.class);
                if (ann == null) {
                    continue;
                }
                Object bean = applicationContext.getBean(beanName);   // 命中动作才实例化
                result.put(ann.name().isEmpty() ? method.getName() : ann.name(),
                        buildEntry(bean, targetClass, ann, method));
            }
        }
        for (ActionEntry entry : result.values()) {
            log.info("[TccAction] 登记本服务动作：{}（try={}, confirm={}, cancel={}）",
                    entry.getActionName(), entry.getTryMethod().getName(),
                    entry.getConfirmMethod().getName(), entry.getCancelMethod().getName());
        }
        return Collections.unmodifiableMap(result);
    }

    /** 名字解析 + 伴生方法定位 + 校验（全部启动期完成） */
    private ActionEntry buildEntry(Object bean, Class<?> targetClass, TccAction ann, Method tryMethod) {
        String actionName = ann.name().isEmpty() ? tryMethod.getName() : ann.name();
        String confirmName = ann.confirm().isEmpty() ? derive("confirm", tryMethod.getName()) : ann.confirm();
        String cancelName = ann.cancel().isEmpty() ? derive("cancel", tryMethod.getName()) : ann.cancel();

        Method confirmMethod = requireMethod(targetClass, confirmName, actionName, "Confirm");
        Method cancelMethod = requireMethod(targetClass, cancelName, actionName, "Cancel");
        // 形参名解析：编译期丢了参数名会导致二阶段参数绑定莫名失败，这里快速失败
        for (Method m : new Method[]{tryMethod, confirmMethod, cancelMethod}) {
            if (parameterNameDiscoverer.getParameterNames(m) == null) {
                throw new IllegalStateException("@TccAction 动作 " + actionName + " 的方法 "
                        + m.getName() + " 无法解析形参名（需以 -parameters 编译）");
            }
        }
        return new ActionEntry(bean, actionName, tryMethod, confirmMethod, cancelMethod);
    }

    /** 约定派生：confirm + 方法名首字母大写（deduct -> confirmDeduct） */
    private static String derive(String prefix, String methodName) {
        return prefix + Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
    }

    private Method requireMethod(Class<?> clazz, String name, String actionName, String phase) {
        Method m = findMethod(clazz, name);
        if (m == null) {
            throw new IllegalStateException("@TccAction 动作 " + actionName + " 按约定找不到 " + phase
                    + " 方法 " + name + "（检查方法名，或在注解里显式指定 confirm/cancel）");
        }
        m.setAccessible(true);
        return m;
    }

    private Method findMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
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
