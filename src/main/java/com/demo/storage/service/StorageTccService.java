package com.demo.storage.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 库存分支 TCC 三个阶段的实现（TCC 参与方核心）
 *
 * 三个阶段的资源语义（available=可用库存，frozen=冻结库存）：
 *   Try    —— 资源预留：available -= n 且 frozen += n（把 n 件库存“冻结”起来给别人用不了）
 *   Confirm —— 提交事务：frozen -= n（冻结量转成真实扣减，库存真正减少）
 *   Cancel  —— 回滚事务：frozen -= n 且 available += n（解冻归还）
 *
 * TCC 三大问题处理（依赖 t_branch_txn_log 分支事务日志）：
 *   幂等     —— Confirm/Cancel 已执行过（终态）时重复调用直接返回成功
 *   空回滚   —— Cancel 时 Try 未执行过（无日志），写入 EMPTY_CANCELLED 标记后直接返回成功
 *   防悬挂   —— Try 时若发现已有回滚标记（CANCELLED / EMPTY_CANCELLED），拒绝执行，
 *              避免迟到的 Try 在 Cancel 之后又把资源冻住、永远无人释放
 */
@Service
public class StorageTccService {

    private static final Logger log = LoggerFactory.getLogger(StorageTccService.class);

    private final JdbcTemplate jdbcTemplate;

    public StorageTccService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Try：冻结库存（资源预留） */
    @Transactional
    public TccResponse tryDeduct(String xid, long productId, int count, boolean mockFail) {
        if (mockFail) {
            // 模拟 Try 阶段业务失败（不写任何数据），用于验证全局 Cancel 回滚
            log.warn("[Storage-Try] xid={} 模拟 Try 阶段业务失败", xid);
            return TccResponse.fail("NONE", "模拟 Try 阶段业务失败");
        }

        Map<String, Object> branch = findBranch(xid);
        if (branch != null) {
            String state = (String) branch.get("STATE");
            if ("TRIED".equals(state)) {
                // 幂等：同一 xid 的 Try 已成功执行过，直接返回成功
                log.info("[Storage-Try] xid={} 分支已处于 TRIED，幂等返回成功", xid);
                return TccResponse.ok("TRIED", "幂等：Try 已执行过");
            }
            if ("CANCELLED".equals(state) || "EMPTY_CANCELLED".equals(state)) {
                // 防悬挂：全局事务已回滚，迟到的 Try 拒绝执行
                log.warn("[Storage-Try] xid={} 检测到回滚标记({})，防悬挂：拒绝执行 Try", xid, state);
                return TccResponse.fail(state, "防悬挂：分支已回滚，拒绝迟到的 Try 请求");
            }
        }

        // 冻结库存：条件 available >= count 保证并发安全（影响 0 行即库存不足）
        int rows = jdbcTemplate.update(
                "UPDATE t_storage SET available = available - ?, frozen = frozen + ? " +
                        "WHERE product_id = ? AND available >= ?",
                count, count, productId, count);
        if (rows == 0) {
            log.warn("[Storage-Try] xid={} 商品 {} 库存不足，Try 失败", xid, productId);
            return TccResponse.fail("NONE", "库存不足，Try 失败");
        }

        // 记录分支日志（Try 成功的凭据），与库存变更在同一本地事务内
        jdbcTemplate.update(
                "INSERT INTO t_branch_txn_log(xid, product_id, order_count, state) VALUES (?, ?, ?, 'TRIED')",
                xid, productId, count);
        log.info("[Storage-Try] xid={} 商品 {} 冻结 {} 件成功", xid, productId, count);
        return TccResponse.ok("TRIED", "冻结库存成功");
    }

    /** Confirm：冻结转实扣（frozen -= n，业务真正提交） */
    @Transactional
    public TccResponse confirm(String xid, boolean mockFailConfirm) {
        if (mockFailConfirm) {
            // 模拟 Confirm 阶段失败：不改变任何状态，发起方将重试
            log.warn("[Storage-Confirm] xid={} 模拟 Confirm 阶段失败（等待发起方重试）", xid);
            return TccResponse.fail("TRIED", "模拟 Confirm 阶段失败");
        }

        Map<String, Object> branch = findBranch(xid);
        if (branch == null) {
            // Confirm 必然晚于 Try，正常不会出现；防御性返回失败让协调器重试
            log.warn("[Storage-Confirm] xid={} 无分支日志（Try 未执行），Confirm 失败", xid);
            return TccResponse.fail("NONE", "分支不存在，Confirm 失败");
        }
        String state = (String) branch.get("STATE");
        if ("CONFIRMED".equals(state)) {
            // 幂等：已确认过，重复 Confirm 直接返回成功
            log.info("[Storage-Confirm] xid={} 分支已 CONFIRMED，幂等返回成功", xid);
            return TccResponse.ok("CONFIRMED", "幂等：Confirm 已执行过");
        }
        if (!"TRIED".equals(state)) {
            log.warn("[Storage-Confirm] xid={} 分支状态异常({})，Confirm 失败", xid, state);
            return TccResponse.fail(state, "分支状态异常，Confirm 失败");
        }

        long productId = ((Number) branch.get("PRODUCT_ID")).longValue();
        int count = ((Number) branch.get("ORDER_COUNT")).intValue();
        jdbcTemplate.update("UPDATE t_storage SET frozen = frozen - ? WHERE product_id = ?", count, productId);
        jdbcTemplate.update("UPDATE t_branch_txn_log SET state = 'CONFIRMED' WHERE xid = ?", xid);
        log.info("[Storage-Confirm] xid={} 商品 {} 冻结量 {} 件转为实际扣减", xid, productId, count);
        return TccResponse.ok("CONFIRMED", "库存扣减提交成功");
    }

    /** Cancel：解冻归还（frozen -= n 且 available += n，资源释放） */
    @Transactional
    public TccResponse cancel(String xid) {
        Map<String, Object> branch = findBranch(xid);
        if (branch == null) {
            // 空回滚：Try 未执行（或 Try 未成功落库）就收到了 Cancel。
            // 写入 EMPTY_CANCELLED 标记：既让本次 Cancel 顺利返回成功，
            // 又能挡住之后迟到的 Try（防悬挂）。
            jdbcTemplate.update(
                    "INSERT INTO t_branch_txn_log(xid, state) VALUES (?, 'EMPTY_CANCELLED')", xid);
            log.warn("[Storage-Cancel] xid={} Try 未执行过，空回滚：写入 EMPTY_CANCELLED 标记", xid);
            return TccResponse.ok("EMPTY_CANCELLED", "空回滚：Try 未执行，仅记录标记");
        }

        String state = (String) branch.get("STATE");
        if ("CANCELLED".equals(state) || "EMPTY_CANCELLED".equals(state)) {
            // 幂等：已回滚过，重复 Cancel 直接返回成功
            log.info("[Storage-Cancel] xid={} 分支已回滚({})，幂等返回成功", xid, state);
            return TccResponse.ok(state, "幂等：Cancel 已执行过");
        }
        if ("CONFIRMED".equals(state)) {
            // 已提交的分支不允许回滚
            log.warn("[Storage-Cancel] xid={} 分支已 CONFIRMED，拒绝 Cancel", xid);
            return TccResponse.fail("CONFIRMED", "分支已提交，拒绝 Cancel");
        }

        long productId = ((Number) branch.get("PRODUCT_ID")).longValue();
        int count = ((Number) branch.get("ORDER_COUNT")).intValue();
        jdbcTemplate.update(
                "UPDATE t_storage SET frozen = frozen - ?, available = available + ? WHERE product_id = ?",
                count, count, productId);
        jdbcTemplate.update("UPDATE t_branch_txn_log SET state = 'CANCELLED' WHERE xid = ?", xid);
        log.info("[Storage-Cancel] xid={} 商品 {} 解冻 {} 件归还可用库存", xid, productId, count);
        return TccResponse.ok("CANCELLED", "解冻归还成功");
    }

    /** 查询分支事务日志，不存在返回 null */
    private Map<String, Object> findBranch(String xid) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM t_branch_txn_log WHERE xid = ?", xid);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
