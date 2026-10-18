package com.demo.storage.controller;

import com.demo.storage.service.StorageTccService;
import com.demo.storage.service.TccResponse;
import com.demo.storage.web.StorageTccRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存服务的 TCC 三阶段接口（仅供 order-service 作为发起方远程调用）
 *
 *   POST /storage/try     body: {xid, productId, count, mockFail}
 *   POST /storage/confirm body: {xid, mockFailConfirm}
 *   POST /storage/cancel  body: {xid}
 */
@RestController
@RequestMapping("/storage")
public class StorageTccController {

    private final StorageTccService storageTccService;

    public StorageTccController(StorageTccService storageTccService) {
        this.storageTccService = storageTccService;
    }

    @PostMapping("/try")
    public TccResponse tryDeduct(@RequestBody StorageTccRequest request) {
        return storageTccService.tryDeduct(
                request.getXid(), request.getProductId(), request.getCount(), request.isMockFail());
    }

    @PostMapping("/confirm")
    public TccResponse confirm(@RequestBody StorageTccRequest request) {
        return storageTccService.confirm(request.getXid(), request.isMockFailConfirm());
    }

    @PostMapping("/cancel")
    public TccResponse cancel(@RequestBody StorageTccRequest request) {
        return storageTccService.cancel(request.getXid());
    }
}
