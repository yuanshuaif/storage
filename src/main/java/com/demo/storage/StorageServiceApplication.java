package com.demo.storage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 库存服务：TCC 事务参与方（RM 角色）
 *
 * TCC 动作用 @TccAction 声明（方法自己定位）；对外暴露：
 *   POST /tcc/invoke            通用调用端点（协调器按注册信息调用，任何动作/阶段）
 *   POST /storage/try|confirm|cancel  旧三端点（保留用于手工恢复与直测）
 * 启动时把本服务动作自注册到注册服务（tcc-registry-service）。
 */
@EnableScheduling
@SpringBootApplication
public class StorageServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StorageServiceApplication.class, args);
    }
}
