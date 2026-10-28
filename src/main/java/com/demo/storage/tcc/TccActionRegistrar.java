package com.demo.storage.tcc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 动作自注册器：启动时把本服务所有 @TccAction（含派生出的三阶段方法名）推送到注册服务。
 *
 * 注册前先校验一阶段端点约定：每个动作必须有 POST /tcc/{动作名} 的直调端点，
 * 缺失即启动失败——URL 约定是发起方直调的前提，不能等调用方 404 了才发现。
 *
 * 注册服务可能尚未启动（或中途重启），因此失败后由定时任务周期重试，直到全部注册成功；
 * 注册按 actionName 幂等覆盖，重复注册安全。生产形态在此之上再加心跳续约与下线剔除。
 */
@Component
public class TccActionRegistrar {

    private static final Logger log = LoggerFactory.getLogger(TccActionRegistrar.class);

    private final LocalTccActionRegistry localRegistry;
    private final RestTemplate restTemplate;
    private final String registryBaseUrl;
    private final String selfUrl;
    private final String serviceName;
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;

    private volatile boolean registered = false;

    public TccActionRegistrar(LocalTccActionRegistry localRegistry,
                              ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider,
                              @Value("${tcc.registry.base-url}") String registryBaseUrl,
                              @Value("${tcc.self.url}") String selfUrl,
                              @Value("${spring.application.name}") String serviceName) {
        this.localRegistry = localRegistry;
        this.handlerMappingProvider = handlerMappingProvider;
        this.registryBaseUrl = registryBaseUrl;
        this.selfUrl = selfUrl;
        this.serviceName = serviceName;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        validateTryEndpoints();
        registerAll();
    }

    /** 注册失败兜底：每 10 秒重试一次，直到全部动作注册成功 */
    @Scheduled(fixedDelay = 10_000)
    public void retryRegister() {
        if (!registered) {
            registerAll();
        }
    }

    /** 一阶段端点约定校验：动作必须有 POST /tcc/{动作名}，缺失则启动失败 */
    private void validateTryEndpoints() {
        RequestMappingHandlerMapping handlerMapping = handlerMappingProvider.getIfAvailable();
        if (handlerMapping == null) {
            return;   // 非 Web 环境（demo 不会走到）
        }
        Set<String> registeredPaths = new HashSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            registeredPaths.addAll(info.getDirectPaths());
        }
        for (LocalTccActionRegistry.ActionEntry entry : localRegistry.all()) {
            String path = "/tcc/" + entry.getActionName();
            if (!registeredPaths.contains(path)) {
                throw new IllegalStateException("TCC 动作 " + entry.getActionName()
                        + " 缺少一阶段端点 " + path + "（约定：POST /tcc/{动作名} 直调 Try 方法，"
                        + "请在 Controller 中提供该映射）");
            }
        }
    }

    private void registerAll() {
        int total = 0;
        int ok = 0;
        for (LocalTccActionRegistry.ActionEntry entry : localRegistry.all()) {
            total++;
            // 方法名一律取启动期派生解析的结果（注解属性可能为空走约定）
            TccActionRegistration reg = new TccActionRegistration();
            reg.setActionName(entry.getActionName());
            reg.setServiceName(serviceName);
            reg.setServiceUrl(selfUrl);
            reg.setTryMethod(entry.getTryMethod().getName());
            reg.setConfirmMethod(entry.getConfirmMethod().getName());
            reg.setCancelMethod(entry.getCancelMethod().getName());
            try {
                ResponseEntity<Map> resp = restTemplate.postForEntity(
                        registryBaseUrl + "/tcc/register", reg, Map.class);
                if (resp.getStatusCode().is2xxSuccessful()) {
                    ok++;
                    log.info("[Registrar] 动作 {} 注册成功 -> {}", entry.getActionName(), registryBaseUrl);
                } else {
                    log.warn("[Registrar] 动作 {} 注册响应异常：{}", entry.getActionName(), resp.getStatusCode());
                }
            } catch (Exception e) {
                log.warn("[Registrar] 动作 {} 注册失败（注册服务不可达？稍后重试）：{}",
                        entry.getActionName(), e.getMessage());
            }
        }
        if (total > 0 && ok == total) {
            registered = true;
            log.info("[Registrar] 本服务全部 {} 个 TCC 动作已注册", total);
        }
        // total==0 说明本服务没有 @TccAction，无需注册
        if (total == 0) {
            registered = true;
        }
    }
}
