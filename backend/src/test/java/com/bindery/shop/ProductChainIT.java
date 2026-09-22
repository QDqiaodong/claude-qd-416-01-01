package com.bindery.shop;

import com.bindery.shop.dto.BizException;
import com.bindery.shop.entity.Product;
import com.bindery.shop.repository.OrderRepository;
import com.bindery.shop.repository.ProductRepository;
import com.bindery.shop.repository.SignoffRepository;
import com.bindery.shop.service.ProductService;
import com.bindery.shop.service.SignoffService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 成品入库链集成测试（跑在真实 MySQL/InnoDB 上，验证 SELECT ... FOR UPDATE 行锁串行）：
 *  - 状态机单向、已入库锁定、数量为正、工单资格等非法请求逐一拒绝；
 *  - 验收场景一：两个人同时把同一笔待入库成品登记为已入库，只能一人成功；
 *  - 验收场景二：换单与目标工单签样退回并发，两种先后顺序都不能出现
 *    成品挂到失去签样资格的工单，失败方整体回滚不留半成品；
 *  - 再用乱序压测反复对撞，断言最终库里不存在“无有效已过签样工单上的成品”。
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
class ProductChainIT {

    @Autowired ProductService productService;
    @Autowired SignoffService signoffService;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired SignoffRepository signoffRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    long machineId, paperId;
    /** 两张已完成且各自带一条已过签样的工单 */
    long orderA, orderB;
    long signoffA, signoffB;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM product");
        jdbc.update("DELETE FROM trial_signoff");
        jdbc.update("DELETE FROM bind_order");
        jdbc.update("DELETE FROM machine");
        jdbc.update("DELETE FROM paper");

        jdbc.update("INSERT INTO paper (id, code, gsm, stock, warn_line) VALUES (1,'T-P',80,999999,0)");
        paperId = 1;
        jdbc.update("INSERT INTO machine (id, code, name, status, machine_type, max_thickness, max_stitches) "
                + "VALUES (91,'T-GB','测试胶装机','空闲','glue',200,NULL)");
        machineId = 91;
        for (long oid : new long[]{101, 102}) {
            jdbc.update("INSERT INTO bind_order (id, machine_id, job_name, qty, status) "
                    + "VALUES (?,?,?,?, '已完成')", oid, machineId, "工单" + oid, 10000);
        }
        orderA = 101;
        orderB = 102;
        jdbc.update("INSERT INTO trial_signoff (id, order_id, machine_id, paper_id, trial_qty, "
                + "spine_thickness, stitch_count, paper_deducted, status) VALUES "
                + "(201,101,91,1,2,10,NULL,1,'已过'),(202,102,91,1,2,10,NULL,1,'已过')");
        signoffA = 201;
        signoffB = 202;
    }

    private Product createPending(long orderId, int qty) {
        return productService.create(Map.of("orderId", orderId, "name", "测试成品", "qty", qty));
    }

    // ---------- 非法请求必须明确报错 ----------

    @Test
    void rejectsIllegalCreateRequests() {
        // 登记时直接写已入库：不允许跳过待入库
        assertBiz("待入库", () -> productService.create(
                Map.of("orderId", orderA, "name", "x", "qty", 1, "status", "已入库")));
        // 任意其它字符串状态
        assertBiz(null, () -> productService.create(
                Map.of("orderId", orderA, "name", "x", "qty", 1, "status", "随便写")));
        // 零 / 负 / 非数字数量
        assertBiz(null, () -> productService.create(Map.of("orderId", orderA, "qty", 0)));
        assertBiz(null, () -> productService.create(Map.of("orderId", orderA, "qty", -5)));
        assertBiz(null, () -> productService.create(Map.of("orderId", orderA, "qty", "abc")));
        assertBiz(null, () -> productService.create(Map.of("orderId", orderA)));
        // 不存在的工单 / 未完成工单 / 没有有效已过签样的工单
        assertBiz(null, () -> productService.create(Map.of("orderId", 999999, "qty", 1)));
        jdbc.update("INSERT INTO bind_order (id, machine_id, job_name, qty, status) "
                + "VALUES (103,91,'进行中工单',100,'进行中')");
        assertBiz(null, () -> productService.create(Map.of("orderId", 103, "qty", 1)));
        jdbc.update("INSERT INTO bind_order (id, machine_id, job_name, qty, status) "
                + "VALUES (104,91,'无签样工单',100,'已完成')");
        assertBiz(null, () -> productService.create(Map.of("orderId", 104, "qty", 1)));
        // 一张都不该落库
        assertEquals(0, productRepository.count());
    }

    @Test
    void enforcesOneWayStatusChainAndLocksAfterStockIn() {
        Product p = createPending(orderA, 100);
        // 非法字符串、倒退类输入
        assertBiz(null, () -> productService.update(p.id, Map.of("status", "已发货")));
        assertBiz(null, () -> productService.update(p.id, Map.of("status", "  ")));
        // 待入库重复设待入库也拒绝（没有意义的状态写）
        assertBiz(null, () -> productService.update(p.id, Map.of("status", "待入库")));

        productService.update(p.id, Map.of("status", "已入库"));
        Product stocked = productRepository.findById(p.id).orElseThrow();
        assertEquals("已入库", stocked.status);

        // 已入库：换单、改数量、倒退、重复入库全部锁住
        assertBiz(null, () -> productService.update(p.id, Map.of("orderId", orderB)));
        assertBiz(null, () -> productService.update(p.id, Map.of("qty", 200)));
        assertBiz(null, () -> productService.update(p.id, Map.of("qty", 0)));
        assertBiz(null, () -> productService.update(p.id, Map.of("status", "待入库")));
        assertBiz(null, () -> productService.update(p.id, Map.of("status", "已入库")));
        assertBiz(null, () -> productService.update(p.id, Map.of("status", "随便写")));
        // 名称仍可改
        productService.update(p.id, Map.of("name", "新名称"));
        assertEquals("新名称", productRepository.findById(p.id).orElseThrow().name);
        // 归属工单与数量确实没变
        Product again = productRepository.findById(p.id).orElseThrow();
        assertEquals(orderA, again.orderId);
        assertEquals(100, again.qty);
        assertNull(again.previousOrderId);
    }

    @Test
    void movingPendingProductLeavesAuditTrailAndValidatesTarget() {
        Product p = createPending(orderA, 30);
        // 换到合格工单 B：原工单 A 留在 previousOrderId
        productService.update(p.id, Map.of("orderId", orderB));
        Product moved = productRepository.findById(p.id).orElseThrow();
        assertEquals(orderB, moved.orderId);
        assertEquals(orderA, moved.previousOrderId);
        assertEquals(30, moved.qty);

        // 换到不存在 / 未完成工单
        assertBiz(null, () -> productService.update(p.id, Map.of("orderId", 888888L)));
        assertBiz(null, () -> productService.update(p.id, Map.of("orderId", 103L)));
        // 失败不留半成品：仍是 B、留痕仍是 A
        Product unchanged = productRepository.findById(p.id).orElseThrow();
        assertEquals(orderB, unchanged.orderId);
        assertEquals(orderA, unchanged.previousOrderId);

        // 第三张已完成工单：签样正常退回（上面没有成品，门禁放行）后即失去成品资格
        jdbc.update("INSERT INTO bind_order (id, machine_id, job_name, qty, status) "
                + "VALUES (105,91,'失格工单',100,'已完成')");
        jdbc.update("INSERT INTO trial_signoff (id, order_id, machine_id, paper_id, trial_qty, "
                + "spine_thickness, stitch_count, paper_deducted, status) VALUES "
                + "(205,105,91,1,2,10,NULL,1,'已过')");
        signoffService.update(205L, Map.of("status", "退回"));
        assertBiz(null, () -> productService.update(p.id, Map.of("orderId", 105L)));
        Product stillB = productRepository.findById(p.id).orElseThrow();
        assertEquals(orderB, stillB.orderId);
        assertEquals(orderA, stillB.previousOrderId, "换到失格工单失败，不留新留痕");

        // B 的签样被人退回后（底层模拟历史/越权写），从失格工单换到合格工单 A 是允许的补救路径
        jdbc.update("UPDATE trial_signoff SET status = '退回' WHERE id = ?", signoffB);
        productService.update(p.id, Map.of("orderId", orderA));
        Product backA = productRepository.findById(p.id).orElseThrow();
        assertEquals(orderA, backA.orderId);
        assertEquals(orderB, backA.previousOrderId, "留痕更新为上一张工单 B");
        assertNoOrphanProduct();
    }

    @Test
    void rejectSignoffBlockedWhileProductsHangOnOrder() {
        Product p1 = createPending(orderA, 10);
        // A 上有待入库成品：已过签样不能退回
        assertBiz(null, () -> signoffService.update(signoffA, Map.of("status", "退回")));
        assertEquals("已过", signoffRepository.findById(signoffA).orElseThrow().status);

        // 换到 B 之后，A 上没有成品了，可以退回
        productService.update(p1.id, Map.of("orderId", orderB));
        signoffService.update(signoffA, Map.of("status", "退回"));
        assertEquals("退回", signoffRepository.findById(signoffA).orElseThrow().status);
        // 但此时再想入库（成品在 B），B 仍有效，正常；把 B 也退回则被挡
        productService.update(p1.id, Map.of("status", "已入库"));
        assertBiz(null, () -> signoffService.update(signoffB, Map.of("status", "退回")));
    }

    // ---------- 验收场景一：两个人同时把同一笔登记为已入库 ----------

    @Test
    void concurrentStockIn_succeedsExactlyOnce() throws Exception {
        Product p = createPending(orderA, 50);
        int n = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicReference<Throwable> winner = new AtomicReference<>();
        AtomicReference<Throwable> loser = new AtomicReference<>();
        AtomicReference<Integer> winnerIdx = new AtomicReference<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    tx.executeWithoutResult(s -> productService.update(p.id, Map.of("status", "已入库")));
                    synchronized (winnerIdx) {
                        if (winnerIdx.get() == null) winnerIdx.set(idx);
                        else fail("两个人都成功入库了");
                    }
                } catch (Throwable e) {
                    // 后到者必须拿到明确业务错误（已入库锁定/重复提交），不能是脏写成功
                    Throwable cause = rootCause(e);
                    if (cause instanceof BizException) {
                        if (loser.get() == null) loser.set(cause);
                    } else {
                        loser.compareAndSet(null, cause);
                    }
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        assertNotNull(winnerIdx.get(), "必须有一人入库成功");
        assertNotNull(loser.get(), "另一人必须失败");
        assertInstanceOf(BizException.class, loser.get(),
                "失败者应是业务异常而非数据库脏写/死链: " + loser.get());

        Product finalP = productRepository.findById(p.id).orElseThrow();
        assertEquals("已入库", finalP.status);
        assertEquals(50, finalP.qty);
        assertEquals(orderA, finalP.orderId);
    }

    // ---------- 验收场景二：换单 vs 目标工单签样退回 ----------

    /** 顺序甲：换单先拿到 B 的工单行/签样行锁，退回在 B 工单行上排队；换单提交后，退出门禁看到成品已挂 B，拒绝 */
    @Test
    void moveFirst_thenRejectIsBlocked() throws Exception {
        Product p = createPending(orderA, 20);

        CountDownLatch moveLockedTarget = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        AtomicReference<Throwable> moveErr = new AtomicReference<>();
        AtomicReference<Throwable> rejectErr = new AtomicReference<>();

        Thread mover = new Thread(() -> {
            try {
                tx.executeWithoutResult(s -> {
                    // 独立连接里按真实锁序锁住 B 的工单行与已过签样行，但先不提交
                    jdbc.queryForList("SELECT id FROM bind_order WHERE id = ? FOR UPDATE", orderB);
                    jdbc.queryForList("SELECT id FROM trial_signoff WHERE order_id = ? AND status = '已过' FOR UPDATE", orderB);
                    moveLockedTarget.countDown();
                    try {
                        allowCommit.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    productService.update(p.id, Map.of("orderId", orderB));
                });
            } catch (Throwable e) {
                moveErr.set(rootCause(e));
            }
        });

        Thread rejecter = new Thread(() -> {
            try {
                moveLockedTarget.await();
                signoffService.update(signoffB, Map.of("status", "退回"));
            } catch (Throwable e) {
                rejectErr.set(rootCause(e));
            }
        });

        mover.start();
        assertTrue(moveLockedTarget.await(10, java.util.concurrent.TimeUnit.SECONDS));
        rejecter.start();
        // 给退回一点时间堵在 B 的工单行锁上
        Thread.sleep(500);
        allowCommit.countDown();
        mover.join(30000);
        rejecter.join(30000);

        assertNull(moveErr.get(), "换单应成功: " + moveErr.get());
        assertNotNull(rejectErr.get(), "退回必须被门禁拒绝");
        assertInstanceOf(BizException.class, rejectErr.get());

        Product finalP = productRepository.findById(p.id).orElseThrow();
        assertEquals(orderB, finalP.orderId, "成品换到 B");
        assertEquals(orderA, finalP.previousOrderId, "原工单 A 留痕");
        assertEquals("待入库", finalP.status, "换单不改变待入库状态");
        assertEquals("已过", signoffRepository.findById(signoffB).orElseThrow().status,
                "B 的签样保持已过，没有失去资格");
        assertNoOrphanProduct();
    }

    /** 顺序乙：退回先拿到 B 的工单行/签样行锁并提交为退回，换单随后读到 B 无已过签样，拒绝且整笔回滚 */
    @Test
    void rejectFirst_thenMoveFailsAndRollsBack() throws Exception {
        Product p = createPending(orderA, 20);

        CountDownLatch rejectHolding = new CountDownLatch(1);
        CountDownLatch allowRejectCommit = new CountDownLatch(1);
        AtomicReference<Throwable> moveErr = new AtomicReference<>();

        Thread rejecter = new Thread(() -> {
            try {
                tx.executeWithoutResult(s -> {
                    // 按真实锁序：先工单行再签样行；B 上当前没有成品（成品在 A），门禁将放行
                    jdbc.queryForList("SELECT id FROM bind_order WHERE id = ? FOR UPDATE", orderB);
                    jdbc.queryForList("SELECT id FROM trial_signoff WHERE id = ? FOR UPDATE", signoffB);
                    rejectHolding.countDown();
                    try {
                        allowRejectCommit.await();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    jdbc.update("UPDATE trial_signoff SET status = '退回' WHERE id = ?", signoffB);
                });
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });

        Thread mover = new Thread(() -> {
            try {
                rejectHolding.await();
                productService.update(p.id, Map.of("orderId", orderB));
            } catch (Throwable e) {
                moveErr.set(rootCause(e));
            }
        });

        rejecter.start();
        assertTrue(rejectHolding.await(10, java.util.concurrent.TimeUnit.SECONDS));
        mover.start();
        Thread.sleep(500); // 换单应已堵在 B 的已过签样行锁上
        allowRejectCommit.countDown();
        rejecter.join(30000);
        mover.join(30000);

        assertNotNull(moveErr.get(), "换单必须失败");
        assertInstanceOf(BizException.class, moveErr.get(),
                "应是资格校验失败而非脏写: " + moveErr.get());

        Product finalP = productRepository.findById(p.id).orElseThrow();
        assertEquals(orderA, finalP.orderId, "失败不留半成品：成品仍挂 A");
        assertNull(finalP.previousOrderId, "失败不留半成品：不能写下换单留痕");
        assertEquals(20, finalP.qty);
        assertEquals("待入库", finalP.status);
        assertEquals("退回", signoffRepository.findById(signoffB).orElseThrow().status);
        assertNoOrphanProduct();
    }

    /**
     * 乱序对撞：两个线程分别反复做 入库 / 换单 / 退回，跑完后全库不能存在
     * “挂在没有有效已过签样工单上的成品”，且每笔成品数量为正、状态合法。
     */
    @Test
    void hammer_neverLeavesProductOnDisqualifiedOrder() throws Exception {
        // 准备 6 笔挂在 A 的待入库成品
        for (int i = 0; i < 6; i++) createPending(orderA, 10 + i);

        int rounds = 30;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> fatal = new AtomicReference<>();

        Thread chain = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < rounds; i++) {
                    List<Product> all = productRepository.findAll();
                    for (Product p : all) {
                        if (!"待入库".equals(p.status)) continue;
                        long target = (i % 2 == 0) ? orderB : orderA;
                        try {
                            if (target != p.orderId)
                                productService.update(p.id, Map.of("orderId", target));
                            productService.update(p.id, Map.of("status", "已入库"));
                        } catch (BizException ignore) {
                            // 资格被并发退回拿掉：必须整体失败，不允许半成品
                        }
                    }
                }
            } catch (Throwable t) {
                fatal.set(t);
            }
        });
        Thread rejecter = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < rounds; i++) {
                    try {
                        signoffService.update(i % 2 == 0 ? signoffB : signoffA,
                                Map.of("status", "退回"));
                    } catch (BizException ignore) {
                        // 工单上还挂成品时门禁拒绝是正确结果
                    }
                    // 退回成功的签样由下一轮无法再恢复（设计如此）；为持续对撞，必要时重新置已过
                    for (long sid : new long[]{signoffA, signoffB}) {
                        String st = signoffRepository.findById(sid).orElseThrow().status;
                        if ("退回".equals(st)) {
                            try {
                                jdbc.update("UPDATE trial_signoff SET status='已过' WHERE id=?", sid);
                            } catch (Exception ignore) {
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                fatal.set(t);
            }
        });

        chain.start();
        rejecter.start();
        start.countDown();
        chain.join(60000);
        rejecter.join(60000);
        assertNull(fatal.get(), "不应出现数据库死锁外的致命错误: " + fatal.get());
        assertNoOrphanProduct();
    }

    /** 核心不变量：任何成品的归属工单都必须存在、已完成、且至少有一条已过签样；状态/数量合法 */
    private void assertNoOrphanProduct() {
        List<Product> all = productRepository.findAll();
        for (Product p : all) {
            assertTrue("待入库".equals(p.status) || "已入库".equals(p.status),
                    "成品 #" + p.id + " 状态非法: " + p.status);
            assertTrue(p.qty != null && p.qty > 0, "成品 #" + p.id + " 数量非法: " + p.qty);
            OrderView o = jdbc.query("SELECT * FROM bind_order WHERE id = ?",
                    rs -> rs.next() ? new OrderView(rs.getString("status")) : null, p.orderId);
            assertNotNull(o, "成品 #" + p.id + " 挂在不存在的工单 " + p.orderId);
            assertEquals("已完成", o.status, "成品 #" + p.id + " 挂在未完成工单");
            Integer passed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM trial_signoff WHERE order_id = ? AND status = '已过'",
                    Integer.class, p.orderId);
            assertTrue(passed != null && passed > 0,
                    "成品 #" + p.id + " 挂在已失去已过签样资格的工单 " + p.orderId);
        }
    }

    private record OrderView(String status) {}

    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c;
    }

    private static void assertBiz(String ignored, org.junit.jupiter.api.function.Executable ex) {
        Throwable t = assertThrows(BizException.class, () -> {
            try {
                ex.execute();
            } catch (Throwable e) {
                if (e instanceof BizException b) throw b;
                if (e instanceof RuntimeException r && r.getCause() instanceof BizException b) throw b;
                throw e;
            }
        });
        assertNotNull(t.getMessage());
        assertFalse(t.getMessage().isBlank(), "错误必须有明确提示");
    }
}
