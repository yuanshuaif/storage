package com.demo.storage.controller;

import com.demo.storage.service.StorageTccService;
import com.demo.storage.service.TccResponse;
import com.demo.storage.web.StorageTccRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一阶段（Try）HTTP 入口——URL 即动作名（约定 POST /tcc/{动作名}）
 *
 * 这是普通的 Spring Controller：扁平业务参数、原生绑定、直调业务方法，
 * 不经任何统一入口或反射（发起方对 Try 的调用就是一次正常的业务调用）。
 *
 * 二阶段（Confirm/Cancel）不走这里：由 TccInvokeDispatcher 的统一端点
 * POST /tcc/invoke 按动作名+phase 反射分发（对应 Seata TC 回调 RM 的形态）。
 */
@RestController
public class StorageTccController {

    private final StorageTccService storageTccService;

    public StorageTccController(StorageTccService storageTccService) {
        this.storageTccService = storageTccService;
    }

    /** POST /tcc/deduct —— 动作 deduct 的 Try：冻结库存 */
    @PostMapping("/tcc/deduct")
    public TccResponse deduct(@RequestBody StorageTccRequest request) {
        return storageTccService.deduct(
                request.getXid(), request.getProductId(), request.getCount(), request.isMockFail());
    }
}
