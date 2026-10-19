package com.demo.storage.tcc;

import com.demo.storage.service.TccResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 通用调用端点：协调器对任何 TCC 动作、任何阶段，都只调这一个接口。
 *
 *   POST /tcc/invoke
 *   { "xid": "...", "actionName": "deductStorage", "phase": "TRY", "params": {...} }
 *
 * 分发规则：
 *   1. actionName -> 本服务 @TccAction 登记（方法由参与方自己声明，协调器不感知）
 *   2. phase      -> TRY / CONFIRM / CANCEL 对应的方法
 *   3. 形参绑定   -> 形参名为 xid / actionName 的取信封字段，其余从 params 按名取并做类型转换
 *
 * 业务方法照常走 @Transactional（反射调用落在 CGLIB 代理上，事务拦截不丢失）。
 * 对应 Seata RM 的 Resource 机制：TC 报 branchId，RM 按注册的资源反射执行。
 */
@RestController
public class TccInvokeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TccInvokeDispatcher.class);

    private final LocalTccActionRegistry localRegistry;

    public TccInvokeDispatcher(LocalTccActionRegistry localRegistry) {
        this.localRegistry = localRegistry;
    }

    @PostMapping("/tcc/invoke")
    public TccResponse invoke(@RequestBody TccInvokeRequest request) {
        String phase = request.getPhase();
        try {
            LocalTccActionRegistry.ActionEntry entry = localRegistry.find(request.getActionName());
            if (entry == null) {
                log.warn("[Dispatcher] 未知动作：{}", request.getActionName());
                return TccResponse.fail("NONE", "本服务不存在 TCC 动作：" + request.getActionName());
            }
            Method method = entry.methodOf(phase);
            if (method == null) {
                return TccResponse.fail("NONE", "非法阶段：" + phase);
            }

            Object[] args = bindArguments(method, request);
            Object result = method.invoke(entry.getBean(), args);

            log.info("[Dispatcher] xid={} 动作 {} 阶段 {} 分发完成 -> {}",
                    request.getXid(), request.getActionName(), phase, describe(result));
            if (result instanceof TccResponse) {
                return (TccResponse) result;
            }
            return TccResponse.ok("OK", "调用完成");

        } catch (InvocationTargetException e) {
            // 反射调用会把业务异常包一层，取根因
            Throwable cause = e.getTargetException() == null ? e : e.getTargetException();
            log.error("[Dispatcher] xid={} 动作 {} 阶段 {} 执行异常：{}",
                    request.getXid(), request.getActionName(), phase, cause.getMessage(), cause);
            return TccResponse.fail("NONE", "参与方执行异常：" + cause.getMessage());
        } catch (Exception e) {
            log.error("[Dispatcher] xid={} 动作 {} 阶段 {} 分发失败：{}",
                    request.getXid(), request.getActionName(), phase, e.getMessage(), e);
            return TccResponse.fail("NONE", "分发失败：" + e.getMessage());
        }
    }

    /** 按形参名绑定参数：xid/actionName 取信封，其余取 params；JSON 类型 -> 形参类型 */
    private Object[] bindArguments(Method method, TccInvokeRequest request) {
        String[] names = localRegistry.parameterNamesOf(method);
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            String name = names == null ? null : names[i];
            Object value;
            if ("xid".equals(name)) {
                value = request.getXid();
            } else if ("actionName".equals(name)) {
                value = request.getActionName();
            } else if ("phase".equals(name)) {
                value = request.getPhase();
            } else {
                value = request.getParams() == null ? null : request.getParams().get(name);
            }
            args[i] = convert(value, types[i]);
        }
        return args;
    }

    /** JSON 反序列化值（Integer/Long/Boolean/String...）-> 方法形参类型的转换 */
    private Object convert(Object value, Class<?> type) {
        if (value == null) {
            // 基本类型给默认值（如 params 未带 mockFail -> false），引用类型给 null
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }
        if (type == long.class || type == Long.class) {
            return ((Number) value).longValue();
        }
        if (type == int.class || type == Integer.class) {
            return ((Number) value).intValue();
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        if (type == String.class) {
            return String.valueOf(value);
        }
        return value;
    }

    private String describe(Object result) {
        if (result instanceof TccResponse) {
            TccResponse r = (TccResponse) result;
            return "success=" + r.isSuccess() + ", state=" + r.getState();
        }
        return String.valueOf(result);
    }
}
