package com.demo.storage.tcc;

/**
 * 注册报文（参与方 -> 注册服务）
 *
 * 与 tcc-registry-service 的 TccActionRegistration 字段一致。
 * demo 各服务是独立工程、无公共 jar，故各持一份同构 DTO（真实框架里这就是 SDK 包）。
 */
public class TccActionRegistration {

    private String actionName;
    private String serviceName;
    private String serviceUrl;
    private String tryMethod;
    private String confirmMethod;
    private String cancelMethod;

    public String getActionName() {
        return actionName;
    }

    public void setActionName(String actionName) {
        this.actionName = actionName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    public String getTryMethod() {
        return tryMethod;
    }

    public void setTryMethod(String tryMethod) {
        this.tryMethod = tryMethod;
    }

    public String getConfirmMethod() {
        return confirmMethod;
    }

    public void setConfirmMethod(String confirmMethod) {
        this.confirmMethod = confirmMethod;
    }

    public String getCancelMethod() {
        return cancelMethod;
    }

    public void setCancelMethod(String cancelMethod) {
        this.cancelMethod = cancelMethod;
    }
}
