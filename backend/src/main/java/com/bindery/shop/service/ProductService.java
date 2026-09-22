package com.bindery.shop.service;

import com.bindery.shop.dto.BizException;
import com.bindery.shop.entity.*;
import com.bindery.shop.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Service
public class ProductService {
    /** 成品状态只有这两个取值，且只能单向：待入库 → 已入库，不能跳过、不能倒退、不能写别的字符串 */
    public static final String PENDING = "待入库";
    public static final String IN_STOCK = "已入库";

    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired SignoffRepository signoffRepository;

    public List<Product> list() {
        return productRepository.findAll();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static Integer intV(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static Long longV(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static int requirePositiveQty(Map<String, Object> m) {
        Integer q = intV(m, "qty");
        if (q == null) throw new BizException("数量必填");
        if (q <= 0) throw new BizException("数量必须大于 0");
        return q;
    }

    /** 锁工单行（SELECT ... FOR UPDATE）；不存在直接报错 */
    private Order lockOrder(Long orderId) {
        Order o = orderRepository.findByIdForUpdate(orderId);
        if (o == null) throw new BizException("归属工单不存在");
        return o;
    }

    /**
     * 入库链门禁（调用方必须已持有该工单行锁）：工单必须仍已完成，
     * 且仍保留有效的已过签样（签样行 FOR UPDATE 当前读）。
     * 与并发签样退回在同一把工单行锁上严格串行：
     * 退回先提交，这里立即读到没有已过签样并拒绝，不按点下去之前的“已过”印象落库。
     */
    private void requireQualifiedDoneOrder(Order o) {
        if (!"已完成".equals(o.status))
            throw new BizException("归属工单「#" + o.id + " " + o.jobName + "」不是已完成状态，不能挂成品");
        if (signoffRepository.findPassedByOrderIdForUpdate(o.id).isEmpty())
            throw new BizException("工单「#" + o.id + " " + o.jobName + "」没有有效的已过签样，成品不能建档/入库/换单过去");
    }

    @Transactional
    public Product create(Map<String, Object> m) {
        Long orderId = longV(m, "orderId");
        if (orderId == null) throw new BizException("归属工单必填");

        int qty = requirePositiveQty(m);

        // 成品只能从待入库起步：请求里带“已入库”或其它任何字符串都拒绝，
        // 不允许登记时一步写成已入库（入库只能通过后续的状态推进动作落账）
        String status = str(m, "status");
        if (status != null && !status.isBlank() && !PENDING.equals(status))
            throw new BizException("新登记成品只能是「待入库」，不能直接建成「" + status + "」");

        // 统一锁序：先锁工单行（与签样退回共用的串行点），再锁签样行做门禁，最后插成品
        Order o = lockOrder(orderId);
        requireQualifiedDoneOrder(o);

        Product p = new Product();
        p.orderId = orderId;
        p.previousOrderId = null;
        p.name = str(m, "name");
        p.qty = qty;
        p.status = PENDING;
        return productRepository.save(p);
    }

    @Transactional
    public Product update(Long id, Map<String, Object> m) {
        // 先用标量查询拿当前归属工单号（只查 id，不把实体装进一级缓存），
        // 随后按统一锁序进入：工单行 → 成品行 → 签样行。
        // 成品行必须是本事务第一次加载该实体，findByIdForUpdate 才会真正带 FOR UPDATE
        // 读到先提交事务的最终版本；若先用普通 findById 预热缓存，行锁当前读会被缓存旧快照架空。
        Long currentOrderId = productRepository.findOrderIdById(id);
        if (currentOrderId == null) throw new BizException("成品不存在");

        Long targetOrderId = null;
        boolean wantsMove = false;
        if (m.containsKey("orderId")) {
            targetOrderId = longV(m, "orderId");
            if (targetOrderId == null) throw new BizException("归属工单不能为空");
            wantsMove = !targetOrderId.equals(currentOrderId);
        }

        if (wantsMove) {
            // 换单涉及两张工单：按 id 升序同时锁原单和目标单，防止两笔交叉换单互锁
            TreeSet<Long> ids = new TreeSet<>();
            ids.add(currentOrderId);
            ids.add(targetOrderId);
            for (Long oid : ids) lockOrder(oid);
        } else {
            lockOrder(currentOrderId);
        }

        // 工单行锁拿到后再锁成品行：两个并发入库同一笔时后来者在本行排队，
        // 等先到者提交后读到“已入库”，重复入库被下面的锁定规则挡下，不会落两笔账。
        Product p = productRepository.findByIdForUpdate(id);
        if (p == null) throw new BizException("成品不存在");
        // 等工单行锁期间这笔成品可能已被另一笔换单挪走：不猜测、不落半条账，
        // 让用户刷新后按最新归属关系重试。
        if (!p.orderId.equals(currentOrderId))
            throw new BizException("该成品的归属工单刚被调整，请刷新后重试");

        if (m.containsKey("status")) {
            String ns = str(m, "status");
            if (ns == null || ns.isBlank()) throw new BizException("状态不能为空");
            if (!PENDING.equals(ns) && !IN_STOCK.equals(ns))
                throw new BizException("成品状态必须是 待入库/已入库，不能写成「" + ns + "」");
        }

        if (IN_STOCK.equals(p.status)) {
            // 已入库即终点：归属工单、数量、状态全部锁住，只允许改名称
            if (m.containsKey("orderId"))
                throw new BizException("成品已入库，归属工单已锁，不能再换单");
            if (m.containsKey("qty"))
                throw new BizException("成品已入库，数量已锁，不能再修改");
            if (m.containsKey("status")) {
                String ns = str(m, "status");
                if (!IN_STOCK.equals(ns))
                    throw new BizException("成品已入库，不能回退到「" + ns + "」");
                throw new BizException("成品已入库，无需重复提交入库");
            }
            if (m.containsKey("name")) p.name = str(m, "name");
            return productRepository.save(p);
        }

        // —— 以下都是待入库成品 ——
        if (m.containsKey("qty")) p.qty = requirePositiveQty(m);
        if (m.containsKey("name")) p.name = str(m, "name");

        boolean stockInRequested = m.containsKey("status") && IN_STOCK.equals(str(m, "status"));
        if (m.containsKey("status") && PENDING.equals(str(m, "status")))
            throw new BizException("成品已经是待入库状态");

        if (wantsMove) {
            // 目标工单门禁：必须仍已完成且保留有效已过签样（已持有目标工单行锁，签样当前读）
            Order target = orderRepository.findById(targetOrderId).orElseThrow(() -> new BizException("归属工单不存在"));
            requireQualifiedDoneOrder(target);
            // 原工单号留在成品上，新旧工单关系页面可核对
            p.previousOrderId = currentOrderId;
            p.orderId = targetOrderId;
        }

        if (stockInRequested) {
            // 入库前门禁复查：待入库期间归属工单的签样可能已被退回。
            // 换单同请求到达时目标单门禁刚查过（同一事务内的当前读），不重复。
            if (!wantsMove) {
                Order o = orderRepository.findById(p.orderId).orElseThrow(() -> new BizException("归属工单不存在"));
                requireQualifiedDoneOrder(o);
            }
            p.status = IN_STOCK;
        }
        return productRepository.save(p);
    }
}
