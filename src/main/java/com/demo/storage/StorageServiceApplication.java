package com.demo.storage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 库存服务：TCC 事务参与方（RM 角色），向发起方暴露 Try / Confirm / Cancel 三个接口
 */
@SpringBootApplication
public class StorageServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StorageServiceApplication.class, args);
    }
}
