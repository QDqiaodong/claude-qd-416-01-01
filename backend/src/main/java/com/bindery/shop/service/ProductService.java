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

    private Order requireDoneOrder(Long orderId) {
        if (orderId == null) throw new BizException("归属工单必填");
        Order o = orderRepository.findById(orderId).orElse(null);
        if (o == null) throw new BizException("归属工单不存在");
        if (!"已完成".equals(o.status)) throw new BizException("归属工单必须已完成");
        // 试装口径：挂着的签样是退回或仍停在未过，成品一律不能建档。
        // 锁行当前读：并发的退回一旦提交，这里立即按退回结果拦住，
        // 不按点下去之前的“已过”印象落库。
        if (signoffRepository.findPassedByOrderIdForUpdate(orderId).isEmpty())
            throw new BizException("该工单没有已过签样，成品不能建档入账");
        return o;
    }

    @Transactional
    public Product create(Map<String, Object> m) {
        Long orderId = longV(m, "orderId");
        requireDoneOrder(orderId);
        Product p = new Product();
        p.orderId = orderId;
        p.name = str(m, "name");
        p.qty = intV(m, "qty");
        String status = str(m, "status");
        p.status = (status == null || status.isBlank()) ? "待入库" : status;
        return productRepository.save(p);
    }

    @Transactional
    public Product update(Long id, Map<String, Object> m) {
        Product p = productRepository.findById(id).orElseThrow(() -> new BizException("成品不存在"));
        if (m.containsKey("name")) p.name = str(m, "name");
        if (m.containsKey("qty")) p.qty = intV(m, "qty");
        if (m.containsKey("status")) {
            String ns = str(m, "status");
            if (ns != null && !ns.isBlank()) p.status = ns;
        }
        if (m.containsKey("orderId")) {
            Long oid = longV(m, "orderId");
            requireDoneOrder(oid);
            p.orderId = oid;
        }
        return productRepository.save(p);
    }
}
