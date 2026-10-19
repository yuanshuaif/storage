package com.demo.storage.tcc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 动作自注册器：启动时把本服务所有 @TccAction 推送到注册服务。
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

    private volatile boolean registered = false;

    public TccActionRegistrar(LocalTccActionRegistry localRegistry,
                              @Value("${tcc.registry.base-url}") String registryBaseUrl,
                              @Value("${tcc.self.url}") String selfUrl,
                              @Value("${spring.application.name}") String serviceName) {
        this.localRegistry = localRegistry;
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
        registerAll();
    }

    /** 注册失败兜底：每 10 秒重试一次，直到全部动作注册成功 */
    @Scheduled(fixedDelay = 10_000)
    public void retryRegister() {
        if (!registered) {
            registerAll();
        }
    }

    private void registerAll() {
        int total = 0;
        int ok = 0;
        for (LocalTccActionRegistry.ActionEntry entry : localRegistry.all()) {
            total++;
            TccAction ann = entry.getAnnotation();
            TccActionRegistration reg = new TccActionRegistration();
            reg.setActionName(ann.name());
            reg.setServiceName(serviceName);
            reg.setServiceUrl(selfUrl);
            reg.setTryMethod(ann.tryMethod());
            reg.setConfirmMethod(ann.confirmMethod());
            reg.setCancelMethod(ann.cancelMethod());
            try {
                ResponseEntity<Map> resp = restTemplate.postForEntity(
                        registryBaseUrl + "/tcc/register", reg, Map.class);
                if (resp.getStatusCode().is2xxSuccessful()) {
                    ok++;
                    log.info("[Registrar] 动作 {} 注册成功 -> {}", ann.name(), registryBaseUrl);
                } else {
                    log.warn("[Registrar] 动作 {} 注册响应异常：{}", ann.name(), resp.getStatusCode());
                }
            } catch (Exception e) {
                log.warn("[Registrar] 动作 {} 注册失败（注册服务不可达？稍后重试）：{}", ann.name(), e.getMessage());
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
