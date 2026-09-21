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
public class OrderService {
    @Autowired OrderRepository orderRepository;
    @Autowired MachineRepository machineRepository;
    @Autowired SignoffRepository signoffRepository;

    public List<Order> list() {
        return orderRepository.findAll();
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

    private static int rank(String s) {
        if ("待排".equals(s)) return 0;
        if ("进行中".equals(s)) return 1;
        if ("已完成".equals(s)) return 2;
        throw new BizException("未知工单状态: " + s);
    }

    private static void validStatus(String s) {
        if (!"待排".equals(s) && !"进行中".equals(s) && !"已完成".equals(s))
            throw new BizException("工单状态必须是 待排/进行中/已完成");
    }

    @Transactional
    public Order create(Map<String, Object> m) {
        Long machineId = longV(m, "machineId");
        if (machineId == null) throw new BizException("machineId 必填");
        if (machineRepository.findById(machineId).orElse(null) == null) throw new BizException("机器不存在");
        String status = str(m, "status");
        if (status == null || status.isBlank()) status = "待排";
        validStatus(status);
        if ("进行中".equals(status))
            throw new BizException("新工单没有已过签样，不能一开单就进进行中");
        Order o = new Order();
        o.machineId = machineId;
        o.jobName = str(m, "jobName");
        o.qty = intV(m, "qty");
        o.status = status;
        return orderRepository.save(o);
    }

    @Transactional
    public Order update(Long id, Map<String, Object> m) {
        Order o = orderRepository.findById(id).orElseThrow(() -> new BizException("工单不存在"));
        if (m.containsKey("machineId")) {
            Long mid = longV(m, "machineId");
            if (mid == null) throw new BizException("machineId 不能为空");
            if (machineRepository.findById(mid).orElse(null) == null) throw new BizException("机器不存在");
            o.machineId = mid;
        }
        if (m.containsKey("jobName")) o.jobName = str(m, "jobName");
        if (m.containsKey("qty")) o.qty = intV(m, "qty");
        if (m.containsKey("status")) {
            String ns = str(m, "status");
            validStatus(ns);
            int from = rank(o.status), to = rank(ns);
            if (to < from) throw new BizException("工单状态不可回退");
            if (to > from + 1) throw new BizException("工单状态只能逐步推进");
            if ("进行中".equals(ns) && !"进行中".equals(o.status)) {
                // 先锁机台行：与报修、开试装在同一把机台锁上串行，
                // 报修一旦先提交，这里读到维修即拒绝开印。
                Machine machine = machineRepository.findByIdForUpdate(o.machineId);
                if (machine == null) throw new BizException("机器不存在");
                if (!MachineStatus.IDLE.equals(machine.status) && !MachineStatus.RUNNING.equals(machine.status))
                    throw new BizException("机器当前为「" + machine.status + "」状态，不能开印（仅空闲/运行机台可开印）");
                // 试装口径：没有已过签样不能开印（当前读，并发退回一提交即拦住）
                if (signoffRepository.findPassedByOrderIdForUpdate(o.id).isEmpty())
                    throw new BizException("没有已过签样，不能开印");
                if (!orderRepository.findRunningListByMachineIdForUpdate(o.machineId).isEmpty())
                    throw new BizException("该机器已有进行中工单，不能重复接单");
            }
            o.status = ns;
        }
        return orderRepository.save(o);
    }
}
