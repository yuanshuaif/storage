package com.demo.storage.tcc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * TCC 动作声明（参与方自己定位自己的三阶段方法）
 *
 * 标在参与方的 @Service 类上；启动时由 TccActionRegistrar 扫描并自注册到注册服务，
 * 运行时由 TccInvokeDispatcher 按 phase 反射调用对应方法——协调器全程不感知方法细节。
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TccAction {

    /** 业务动作句柄：发起方注册分支时用它引用本动作（全局唯一） */
    String name();

    /** Try 方法名：资源预留 */
    String tryMethod();

    /** Confirm 方法名：提交（必然成功语义） */
    String confirmMethod();

    /** Cancel 方法名：回滚（释放预留） */
    String cancelMethod();
}
