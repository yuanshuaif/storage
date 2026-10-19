package com.demo.storage.tcc;

import java.util.Map;

/**
 * 通用调用信封（协调器 -> 参与方的 /tcc/invoke）
 *
 * xid       ：全局事务 id
 * actionName：要调用的动作句柄（对应本服务某个 @TccAction）
 * phase     ：TRY / CONFIRM / CANCEL
 * params    ：业务参数，按参数名与方法形参绑定（xid、actionName 由信封自动注入同名形参）
 */
public class TccInvokeRequest {

    private String xid;
    private String actionName;
    private String phase;
    private Map<String, Object> params;

    public String getXid() {
        return xid;
    }

    public void setXid(String xid) {
        this.xid = xid;
    }

    public String getActionName() {
        return actionName;
    }

    public void setActionName(String actionName) {
        this.actionName = actionName;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
}
