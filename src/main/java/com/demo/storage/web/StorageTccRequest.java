package com.demo.storage.web;

/**
 * 库存服务 TCC 接口请求体
 */
public class StorageTccRequest {

    private String xid;
    private Long productId;
    private Integer count;
    /** true = 模拟 Try 阶段业务失败 */
    private boolean mockFail;
    /** true = 模拟 Confirm 阶段失败（验证发起方重试） */
    private boolean mockFailConfirm;

    public String getXid() {
        return xid;
    }

    public void setXid(String xid) {
        this.xid = xid;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public boolean isMockFail() {
        return mockFail;
    }

    public void setMockFail(boolean mockFail) {
        this.mockFail = mockFail;
    }

    public boolean isMockFailConfirm() {
        return mockFailConfirm;
    }

    public void setMockFailConfirm(boolean mockFailConfirm) {
        this.mockFailConfirm = mockFailConfirm;
    }
}
