package com.bindery.shop.service;

import com.bindery.shop.dto.BizException;
import com.bindery.shop.entity.*;
import com.bindery.shop.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class ProductService {
    /** 成品状态机只有两个取值：待入库 → 已入库，不能跳过（登记即已入库）也不能倒退 */
    public static final String PENDING = "待入库";
    public static final String STOCKED = "已入库";

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

    /**
     * 入库链当前读：工单必须存在、已完成、且此刻仍挂着已过签样。
     * 签样行走 SELECT ... FOR UPDATE：并发的签样退回一旦先提交，
     * 这里立即按退回后的结果拦住，不按点下去之前的“已过”印象落库。
     */
    private Order requireDoneOrder(Long orderId) {
        if (orderId == null) throw new BizException("归属工单必填");
        Order o = orderRepository.findById(orderId).orElse(null);
        if (o == null) throw new BizException("归属工单不存在");
        if (!"已完成".equals(o.status)) throw new BizException("归属工单必须已完成");
        if (signoffRepository.findPassedByOrderIdForUpdate(orderId).isEmpty())
            throw new BizException("该工单没有已过签样，成品不能建档入账");
        return o;
    }

    private static Integer requireQty(Integer q) {
        if (q == null) throw new BizException("数量必填");
        if (q <= 0) throw new BizException("数量必须大于 0，不能是零或负数");
        return q;
    }

    @Transactional
    public Product create(Map<String, Object> m) {
        Long orderId = longV(m, "orderId");
        requireDoneOrder(orderId);
        Integer qty = requireQty(intV(m, "qty"));
        // 入库链第一步只能是待入库：登记即已入库属于跳过，一律拒绝
        String status = str(m, "status");
        if (status != null && !status.isBlank() && !PENDING.equals(status))
            throw new BizException("成品只能以「待入库」登记，不能一登记就已入库；入库请走卡片上的登记入库动作");
        Product p = new Product();
        p.orderId = orderId;
        p.name = str(m, "name");
        p.qty = qty;
        p.status = PENDING;
        return productRepository.save(p);
    }

    @Transactional
    public Product update(Long id, Map<String, Object> m) {
        // 先锁成品行当前读：并发的入库/换单在同一行上串行，
        // 后到的请求等先提交的落库后再判定，不会两边各按自己看到的状态各写一笔。
        Product p = productRepository.findByIdForUpdate(id);
        if (p == null) throw new BizException("成品不存在");
        boolean stocked = STOCKED.equals(p.status);

        // 状态机：只认 待入库/已入库 两个固定取值，其余字符串一律明确报错
        String ns = m.containsKey("status") ? str(m, "status") : null;
        if (m.containsKey("status")) {
            if (ns == null || ns.isBlank()) throw new BizException("成品状态不能为空");
            if (!PENDING.equals(ns) && !STOCKED.equals(ns))
                throw new BizException("成品状态必须是 待入库/已入库，收到非法取值：" + ns);
            // 已入库是终点：不能重复入库，也不能改回待入库或任何别的值
            if (stocked) throw new BizException("成品已入库，不能重复入库，也不能改回待入库");
        }

        Long newOrderId = m.containsKey("orderId") ? longV(m, "orderId") : null;
        boolean wantStockIn = STOCKED.equals(ns);
        boolean wantReassign = m.containsKey("orderId") && newOrderId != null && !newOrderId.equals(p.orderId);
        if (wantStockIn && wantReassign)
            throw new BizException("换单和入库必须分两步：先换归属工单，再登记入库");

        if (wantStockIn) {
            // 入库动作：入库链当前读，归属工单仍已完成且仍挂着已过签样才放行
            requireDoneOrder(p.orderId);
            p.status = STOCKED;
        }

        if (m.containsKey("qty")) {
            if (stocked) throw new BizException("成品已入库，数量已锁定，不能再改");
            p.qty = requireQty(intV(m, "qty"));
        }
        if (m.containsKey("orderId")) {
            if (newOrderId == null) throw new BizException("归属工单不能为空");
            if (!newOrderId.equals(p.orderId)) {
                if (stocked) throw new BizException("成品已入库，归属工单已锁定，不能再换单");
                // 目标工单必须仍是已完成且保留有效的已过签样（锁行当前读，
                // 并发的签样退回先提交则这里立即拦住，不会挂到已失去签样资格的工单上）
                requireDoneOrder(newOrderId);
                // 留痕：从哪张工单转来，页面上原单与新单的关系可核对
                p.prevOrderId = p.orderId;
                p.orderId = newOrderId;
            }
        }
        if (m.containsKey("name")) p.name = str(m, "name");
        return productRepository.save(p);
    }
}
