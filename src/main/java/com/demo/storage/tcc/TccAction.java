package com.demo.storage.tcc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * TCC 动作声明——标在【Try 业务方法】上（方法级，对应 Seata 的 @TwoPhaseBusinessAction）
 *
 * 参与方只在正常业务方法上标注；动作名与伴生方法按命名约定自动派生：
 *   动作名:   方法名                    deduct(...)
 *   Confirm: "confirm" + 方法名首字母大写  confirmDeduct(...)
 *   Cancel:  "cancel"  + 方法名首字母大写  cancelDeduct(...)
 *
 * 三个属性都可缺省（走约定）；约定不适用时（跨类、历史命名）显式指定覆盖。
 * LocalTccActionRegistry 在启动期解析派生并强校验：伴生方法不存在则应用启动失败——
 * 拼写错误绝不拖到二阶段才爆炸。
 *
 * 调用形态：
 *   一阶段：POST /tcc/{动作名} 专属业务端点直调（扁平参数，Spring 原生绑定）
 *   二阶段：POST /tcc/invoke 统一信封，按动作名+phase 反射分发（对应 Seata TC 回调 RM）
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TccAction {

    /** 动作句柄（发起方按它调用与登记，一阶段端点 URL 也由它生成）；缺省取方法名 */
    String name() default "";

    /** Confirm 方法名：提交（必然成功语义）；缺省按约定派生 */
    String confirm() default "";

    /** Cancel 方法名：回滚（释放预留）；缺省按约定派生 */
    String cancel() default "";
}
