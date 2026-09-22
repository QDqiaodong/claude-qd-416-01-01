package com.bindery.shop.service;

import com.bindery.shop.dto.BizException;
import com.bindery.shop.entity.*;
import com.bindery.shop.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SignoffService {
    @Autowired SignoffRepository signoffRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired MachineRepository machineRepository;
    @Autowired PaperRepository paperRepository;
    @Autowired ProductRepository productRepository;

    public List<Signoff> list(Long orderId) {
        if (orderId != null) return signoffRepository.findByOrderId(orderId);
        return signoffRepository.findAll();
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
     * 取机台行锁（SELECT ... FOR UPDATE）并校验可用：
     * 报修与开试装都从这把锁进入，同机台严格串行，谁先提交谁落库；
     * 只放行空闲/运行，维修中或历史脏值（检修/停机等）一律不允许在该机开试装。
     */
    private Machine lockUsableMachine(Long machineId) {
        if (machineId == null) throw new BizException("装订机必填");
        Machine machine = machineRepository.findByIdForUpdate(machineId);
        if (machine == null) throw new BizException("装订机不存在");
        if (!MachineStatus.IDLE.equals(machine.status) && !MachineStatus.RUNNING.equals(machine.status))
            throw new BizException("机器当前为「" + machine.status + "」状态，不能开试装签样（仅空闲/运行机台可试装）");
        return machine;
    }

    /** 库存够则如数扣减并返回 true；不够则一张都不扣，返回 false */
    private boolean tryDeductPaper(Long paperId, Integer qty) {
        Paper p = paperRepository.findByIdForUpdate(paperId);
        if (p == null) throw new BizException("纸张不存在");
        if (p.stock == null || p.stock < qty) return false;
        p.stock -= qty;
        paperRepository.save(p);
        return true;
    }

    @Transactional
    public Signoff create(Map<String, Object> m) {
        Long orderId = longV(m, "orderId");
        if (orderId == null) throw new BizException("归属工单必填");
        Order o = orderRepository.findById(orderId).orElse(null);
        if (o == null) throw new BizException("归属工单不存在");
        if (!"待排".equals(o.status)) throw new BizException("签样只能挂在待排工单上");

        // 先锁机台行再做任何判定：和同一时刻按报修的班长在同一行上排队，
        // 报修先提交则这里读到维修并拒绝，本试装先提交则报修看到未过签样一并作废，
        // 不会两边各按自己看到的状态各写一笔。
        Machine machine = lockUsableMachine(longV(m, "machineId"));

        Long paperId = longV(m, "paperId");
        if (paperId == null) throw new BizException("试装纸张必填");
        if (paperRepository.findById(paperId).orElse(null) == null) throw new BizException("纸张不存在");

        Integer trialQty = intV(m, "trialQty");
        if (trialQty == null || trialQty <= 0) throw new BizException("试装册数必须大于 0");

        Signoff s = new Signoff();
        s.orderId = orderId;
        s.machineId = machine.id;
        s.paperId = paperId;
        s.trialQty = trialQty;
        if (machine instanceof GlueBindMachine) {
            s.spineThickness = intV(m, "spineThickness");
        } else if (machine instanceof SaddleStitchMachine) {
            s.stitchCount = intV(m, "stitchCount");
        }
        // 试装用纸从纸张库存扣：库存够才扣，不够一张都不扣，签样停在未过
        s.paperDeducted = tryDeductPaper(paperId, trialQty);
        s.status = "未过";
        return signoffRepository.save(s);
    }

    @Transactional
    public Signoff update(Long id, Map<String, Object> m) {
        // 只用标量查询取当前状态，不把实体装进一级缓存：
        // 下面的 findByIdForUpdate 必须是本事务第一次加载该实体，才能真正按 FOR UPDATE
        // 当前读拿到等锁期间其它事务提交的最终状态。
        String currentStatus = signoffRepository.findStatusById(id);
        if (currentStatus == null) throw new BizException("签样不存在");
        if ("退回".equals(currentStatus)) throw new BizException("签样已退回，不能再修改");

        String target = str(m, "status");
        if (target != null && target.isBlank()) target = null;
        if (target != null && !"未过".equals(target) && !"已过".equals(target) && !"退回".equals(target))
            throw new BizException("签样状态必须是 未过/已过/退回");

        if ("已过".equals(currentStatus)) {
            // 已过签样锁定机台和纸种：开印要对试装当时的那一台机、那一种纸
            if (m.containsKey("machineId") || m.containsKey("paperId"))
                throw new BizException("已过签样不允许再改机台和纸种");
            if (m.containsKey("trialQty") || m.containsKey("spineThickness") || m.containsKey("stitchCount"))
                throw new BizException("已过签样不允许再改试装参数");
            if (target == null || "已过".equals(target))
                return signoffRepository.findById(id).orElseThrow(() -> new BizException("签样不存在"));
            if (!"退回".equals(target)) throw new BizException("已过签样只能改为退回");
            Long lockedOrderId = signoffRepository.findOrderIdById(id);
            // 入库链串行点：先锁归属工单行，再锁签样行。
            // 建档 / 入库 / 换单也都从同一把工单行锁进入（换单同时锁原单与目标单，按 id 排序），
            // 因此退回与这些动作不会交错各写各的，只会严格排队，谁先拿到工单行锁谁先落账。
            Order gate = orderRepository.findByIdForUpdate(lockedOrderId);
            if (gate == null) throw new BizException("归属工单不存在，不能退回签样");
            // 锁行当前读：本事务第一次加载该实体，FOR UPDATE 才能读到等锁期间提交的最新状态
            Signoff s = signoffRepository.findByIdForUpdate(id);
            if (s == null || "退回".equals(s.status)) throw new BizException("签样已退回，不能再修改");
            // 入库链门禁：已过签样是挂在该工单上的成品的资格依据，
            // 只要工单还挂着成品，签样就不能退回——已入库成品的归属工单已锁，
            // 待入库成品必须先由登记人换到另一张仍有已过签样的已完成工单。
            // FOR UPDATE 当前读：等工单行锁期间可能刚有换单/入库提交，按最新提交版本计数。
            List<Product> products = productRepository.findByOrderIdForUpdate(s.orderId);
            if (!products.isEmpty()) {
                long pending = products.stream().filter(p -> "待入库".equals(p.status)).count();
                long inStock = products.size() - pending;
                String tip = "该工单上还挂着成品（待入库 " + pending + " 笔 / 已入库 " + inStock
                        + " 笔），退回签样会让成品失去已过签样依据，不能退回；"
                        + "请先把待入库成品换到另一张仍有已过签样的已完成工单"
                        + (inStock > 0 ? "（已入库成品的归属工单已锁定，不能再动）" : "");
                throw new BizException(tip);
            }
            // 退回：已扣掉的试装纸不退回库存
            s.status = "退回";
            return signoffRepository.save(s);
        }

        // 未过签样：统一先锁涉及到的机台行（换机台时按 id 顺序锁两台），再锁签样行。
        // 报修同样先拿机台锁，因此两边在同一机台上严格串行，不存在各写一笔。
        Long currentMachineId = signoffRepository.findMachineIdById(id);
        Set<Long> machineIds = new LinkedHashSet<>();
        machineIds.add(currentMachineId);
        if (m.containsKey("machineId")) {
            Long targetMachineId = longV(m, "machineId");
            if (targetMachineId == null) throw new BizException("装订机不能为空");
            machineIds.add(targetMachineId);
        }
        machineIds.stream().sorted().forEach(this::lockUsableMachine);

        // 机台锁拿到后第一次加载实体即带 FOR UPDATE 当前读复查：
        // 若报修已先提交，这条签样已是退回，不能再改
        Signoff s = signoffRepository.findByIdForUpdate(id);
        if (s == null) throw new BizException("签样不存在");
        if ("退回".equals(s.status)) throw new BizException("签样已随机台报修作废成退回，不能再修改");

        // 未过：允许调整试装信息
        if (m.containsKey("machineId")) {
            // lockUsableMachine 已在上面锁过并校验过目标机台非维修
            Long targetMachineId = longV(m, "machineId");
            Machine nm = machineRepository.findById(targetMachineId)
                    .orElseThrow(() -> new BizException("装订机不存在"));
            s.machineId = nm.id;
        }
        if (m.containsKey("paperId") || m.containsKey("trialQty")) {
            if (Boolean.TRUE.equals(s.paperDeducted))
                throw new BizException("试装纸已扣，不能再换纸或改册数；请退回本条后新开签样");
            if (m.containsKey("paperId")) {
                Long pid = longV(m, "paperId");
                if (pid == null) throw new BizException("试装纸张不能为空");
                if (paperRepository.findById(pid).orElse(null) == null) throw new BizException("纸张不存在");
                s.paperId = pid;
            }
            if (m.containsKey("trialQty")) {
                Integer q = intV(m, "trialQty");
                if (q == null || q <= 0) throw new BizException("试装册数必须大于 0");
                s.trialQty = q;
            }
        }
        if (m.containsKey("spineThickness")) s.spineThickness = intV(m, "spineThickness");
        if (m.containsKey("stitchCount")) s.stitchCount = intV(m, "stitchCount");

        if ("退回".equals(target)) {
            // 退回：已扣掉的试装纸不退回库存
            s.status = "退回";
            return signoffRepository.save(s);
        }
        if ("已过".equals(target)) {
            // 试装纸还没扣上的先扣；库存不够一张不扣，签样停在未过
            if (!Boolean.TRUE.equals(s.paperDeducted)) {
                if (!tryDeductPaper(s.paperId, s.trialQty))
                    throw new BizException("纸张库存不足，签样停在未过（未扣任何纸）");
                s.paperDeducted = true;
            }
            Machine machine = machineRepository.findById(s.machineId).orElseThrow(() -> new BizException("装订机不存在"));
            if (machine instanceof GlueBindMachine g) {
                if (s.spineThickness == null) throw new BizException("胶装机试装必须写下书脊厚度");
                if (g.maxThickness != null && s.spineThickness > g.maxThickness)
                    throw new BizException("书脊厚度 " + s.spineThickness + "mm 超过该机最大厚度 "
                            + g.maxThickness + "mm，不能写成已过");
                s.stitchCount = null;
            } else if (machine instanceof SaddleStitchMachine sm) {
                if (s.stitchCount == null) throw new BizException("骑马钉机试装必须写下订针数");
                if (sm.maxStitches != null && s.stitchCount > sm.maxStitches)
                    throw new BizException("订针数 " + s.stitchCount + " 超过该机最大针数 "
                            + sm.maxStitches + "，不能写成已过");
                s.spineThickness = null;
            }
            s.status = "已过";
        }
        return signoffRepository.save(s);
    }
}
