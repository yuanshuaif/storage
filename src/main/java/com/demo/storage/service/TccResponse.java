package com.demo.storage.service;

/**
 * TCC 接口统一响应
 *
 * success：本阶段是否成功
 * state  ：分支状态 TRIED / CONFIRMED / CANCELLED / EMPTY_CANCELLED / NONE
 * message：说明信息
 */
public class TccResponse {

    private boolean success;
    private String state;
    private String message;

    public TccResponse() {
    }

    public TccResponse(boolean success, String state, String message) {
        this.success = success;
        this.state = state;
        this.message = message;
    }

    public static TccResponse ok(String state, String message) {
        return new TccResponse(true, state, message);
    }

    public static TccResponse fail(String state, String message) {
        return new TccResponse(false, state, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
