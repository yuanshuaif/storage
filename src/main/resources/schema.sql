-- 库存表：available 可用库存 / frozen 冻结库存（被 Try 阶段预留的部分）
CREATE TABLE IF NOT EXISTS t_storage (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL UNIQUE,
    available  INT    NOT NULL,
    frozen     INT    NOT NULL DEFAULT 0
);

-- 分支事务日志表（TCC 三大问题的实现基础）
-- 主键为 (xid, action_name)：同一全局事务里一个参与方可以承载多个不同动作
-- state 取值：TRIED（Try已执行）/ CONFIRMED / CANCELLED / EMPTY_CANCELLED（空回滚标记）
CREATE TABLE IF NOT EXISTS t_branch_txn_log (
    xid         VARCHAR(64),
    action_name VARCHAR(64) NOT NULL DEFAULT 'deduct',
    product_id  BIGINT,
    order_count INT,
    state       VARCHAR(16) NOT NULL,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (xid, action_name)
);
