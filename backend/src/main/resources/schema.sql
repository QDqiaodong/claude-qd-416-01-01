CREATE TABLE IF NOT EXISTS machine (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    machine_type VARCHAR(16) NOT NULL,
    max_thickness INT,
    max_stitches INT,
    CONSTRAINT chk_machine_status CHECK (status IN ('空闲', '运行', '维修'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bind_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    machine_id BIGINT,
    job_name VARCHAR(64),
    qty INT,
    status VARCHAR(16) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS paper (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL UNIQUE,
    gsm INT,
    stock INT NOT NULL DEFAULT 0,
    warn_line INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS product (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    -- 换单前的上一张归属工单（仅待入库换单时落痕）；已入库后随归属工单一起锁住
    previous_order_id BIGINT,
    name VARCHAR(64),
    qty INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT chk_product_qty CHECK (qty > 0),
    CONSTRAINT chk_product_status CHECK (status IN ('待入库', '已入库')),
    KEY idx_product_order (order_id),
    KEY idx_product_prev_order (previous_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS trial_signoff (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    machine_id BIGINT NOT NULL,
    paper_id BIGINT NOT NULL,
    trial_qty INT NOT NULL,
    spine_thickness INT,
    stitch_count INT,
    paper_deducted TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT '未过',
    KEY idx_signoff_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO machine (id, code, name, status, machine_type, max_thickness, max_stitches) VALUES
 (1, 'GB01', '胶装一号机', '运行', 'glue', 50, NULL),
 (2, 'GB02', '胶装二号机', '空闲', 'glue', 60, NULL),
 (3, 'SS01', '骑马钉一号机', '维修', 'saddle', NULL, 24),
 (4, 'SS02', '骑马钉二号机', '空闲', 'saddle', NULL, 30),
 (5, 'GB03', '胶装三号机', '空闲', 'glue', 45, NULL),
 (6, 'SS03', '骑马钉三号机', '运行', 'saddle', NULL, 20);

INSERT IGNORE INTO bind_order (id, machine_id, job_name, qty, status) VALUES
 (1, 1, '会议手册胶装', 500, '进行中'),
 (2, 1, '年报胶装', 300, '待排'),
 (3, 3, '画册骑马钉', 200, '已完成'),
 (4, 4, '宣传册骑马钉', 800, '待排'),
 (5, 2, '教材胶装', 1000, '已完成'),
 (6, 5, '笔记本胶装', 400, '进行中');

INSERT IGNORE INTO paper (id, code, gsm, stock, warn_line) VALUES
 (1, 'P70', 70, 5000, 1000),
 (2, 'P80', 80, 300, 800),
 (3, 'P100', 100, 2000, 500),
 (4, 'P120', 120, 0, 600),
 (5, 'P157', 157, 1500, 300);

INSERT IGNORE INTO product (id, order_id, name, qty, status) VALUES
 (1, 3, '精装画册', 200, '已入库'),
 (2, 3, '简装画册', 150, '待入库'),
 (3, 5, '胶装教材', 1000, '已入库'),
 (4, 5, '教材样书', 50, '待入库'),
 (5, 3, '画册补印', 80, '已入库');

INSERT IGNORE INTO trial_signoff (id, order_id, machine_id, paper_id, trial_qty, spine_thickness, stitch_count, paper_deducted, status) VALUES
 (1, 2, 1, 1, 5, 32, NULL, 1, '已过'),
 (2, 4, 4, 2, 3, NULL, 18, 1, '未过'),
 (3, 3, 3, 3, 3, NULL, 16, 1, '已过'),
 (4, 5, 2, 1, 4, 30, NULL, 1, '已过');

-- ===== 老库升级（幂等，可重复执行）：成品入库链闭合 =====
-- 1. 补换单留痕列与索引
SET @ddl := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'previous_order_id') = 0,
  'ALTER TABLE product ADD COLUMN previous_order_id BIGINT NULL AFTER order_id',
  'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND INDEX_NAME = 'idx_product_order') = 0,
  'ALTER TABLE product ADD KEY idx_product_order (order_id)',
  'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND INDEX_NAME = 'idx_product_prev_order') = 0,
  'ALTER TABLE product ADD KEY idx_product_prev_order (previous_order_id)',
  'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 先清理历史脏数据（非法状态/零负数量/悬空工单），再上约束
UPDATE product SET status = '待入库' WHERE status NOT IN ('待入库', '已入库');
DELETE FROM product WHERE qty IS NULL OR qty <= 0;
DELETE p FROM product p LEFT JOIN bind_order o ON o.id = p.order_id WHERE o.id IS NULL;

SET @ddl := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND CONSTRAINT_NAME = 'chk_product_qty') = 0,
  'ALTER TABLE product ADD CONSTRAINT chk_product_qty CHECK (qty > 0)',
  'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND CONSTRAINT_NAME = 'chk_product_status') = 0,
  'ALTER TABLE product ADD CONSTRAINT chk_product_status CHECK (status IN (''待入库'', ''已入库''))',
  'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
