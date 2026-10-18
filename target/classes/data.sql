-- 初始库存：商品 1001，可用 100 件，冻结 0 件
MERGE INTO t_storage (product_id, available, frozen) KEY (product_id) VALUES (1001, 100, 0);
