package com.bindery.shop.service;

import com.bindery.shop.dto.BizException;
import com.bindery.shop.entity.*;
import com.bindery.shop.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class MachineService {
    @Autowired MachineRepository machineRepository;
    @Autowired GlueBindMachineRepository glueRepo;
    @Autowired SaddleStitchMachineRepository saddleRepo;
    @Autowired OrderRepository orderRepository;
    @Autowired SignoffRepository signoffRepository;

    /**
     * @param status 三个固定取值之一；传 "异常" 只返回历史脏值机台（检修/停机/随手词），
     *               这类机台按状态筛选时无处可去，专门留一个口子把它们查出来
     */
    public List<Machine> list(String type, String status) {
        List<Machine> all;
        if ("glue".equals(type)) all = new ArrayList<>(glueRepo.findAll());
        else if ("saddle".equals(type)) all = new ArrayList<>(saddleRepo.findAll());
        else all = machineRepository.findAll();

        if (status == null || status.isBlank()) return all;
        List<Machine> out = new ArrayList<>();
        for (Machine m : all) {
            boolean dirty = !MachineStatus.isValid(m.status);
            if ("异常".equals(status)) {
                if (dirty) out.add(m);
            } else if (!dirty && status.equals(m.status)) {
                out.add(m);
            }
        }
        return out;
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

    public Machine create(Map<String, Object> m) {
        String type = str(m, "machineType");
        if (type == null || (!"glue".equals(type) && !"saddle".equals(type)))
            throw new BizException("machineType 必须是 glue(胶装机) 或 saddle(骑马钉机)");
        String code = str(m, "code");
        if (code == null || code.isBlank()) throw new BizException("机器编号必填");
        if (machineRepository.findByCode(code) != null) throw new BizException("机器编号 " + code + " 已存在");
        Machine machine;
        if ("glue".equals(type)) {
            GlueBindMachine g = new GlueBindMachine();
            Integer mt = intV(m, "maxThickness");
            if (mt == null) throw new BizException("胶装机最大厚度(mm)必填");
            g.maxThickness = mt;
            machine = g;
        } else {
            SaddleStitchMachine s = new SaddleStitchMachine();
            Integer ms = intV(m, "maxStitches");
            if (ms == null) throw new BizException("骑马钉机最大针数必填");
            s.maxStitches = ms;
            machine = s;
        }
        machine.code = code;
        machine.name = str(m, "name");
        // 状态只收三个固定取值，不再来什么存什么
        String status = str(m, "status");
        machine.status = (status == null || status.isBlank()) ? MachineStatus.IDLE : MachineStatus.requireValid(status);
        return machineRepository.save(machine);
    }

    @Transactional
    public Machine update(Long id, Map<String, Object> m) {
        Machine machine = machineRepository.findById(id).orElseThrow(() -> new BizException("机器不存在"));
        boolean locked = MachineStatus.RUNNING.equals(machine.status) || MachineStatus.REPAIR.equals(machine.status);
        if (m.containsKey("code")) {
            if (locked) throw new BizException("运行/维修中的机器禁止改编号");
            String code = str(m, "code");
            if (code == null || code.isBlank()) throw new BizException("机器编号必填");
            Machine dup = machineRepository.findByCode(code);
            if (dup != null && !dup.id.equals(id)) throw new BizException("机器编号 " + code + " 已存在");
            machine.code = code;
        }
        if (m.containsKey("name")) {
            if (locked) throw new BizException("运行/维修中的机器禁止改名称");
            machine.name = str(m, "name");
        }
        if (machine instanceof GlueBindMachine g && m.containsKey("maxThickness")) {
            if (locked) throw new BizException("运行/维修中的机器禁止改关键参数");
            g.maxThickness = intV(m, "maxThickness");
        }
        if (machine instanceof SaddleStitchMachine s && m.containsKey("maxStitches")) {
            if (locked) throw new BizException("运行/维修中的机器禁止改关键参数");
            s.maxStitches = intV(m, "maxStitches");
        }
        if (m.containsKey("status")) {
            String ns = str(m, "status");
            if (ns == null || ns.isBlank()) throw new BizException("机器状态不能为空");
            // 进入维修只能走报修（要拦在印工单、连带作废未过签样），这里不允许直接写
            if (MachineStatus.REPAIR.equals(ns)) return reportRepair(id);
            if (!MachineStatus.isValid(ns)) MachineStatus.requireValid(ns);
            // 历史脏值（检修/停机等）必须走报修 → 修好收敛，不能直接改成合法状态蒙混
            if (!MachineStatus.isValid(machine.status))
                throw new BizException("该机台当前是非法状态「" + machine.status
                        + "」，请先走报修流程，修好后状态即收敛回空闲");
            // 从维修恢复只能走修好动作（只落回空闲），不允许在通用编辑里带出维修
            if (MachineStatus.REPAIR.equals(machine.status))
                throw new BizException("维修中的机器请先走“修好”流程恢复，不能直接改状态");
            if (MachineStatus.IDLE.equals(ns) && MachineStatus.RUNNING.equals(machine.status))
                blockIfRunningOrder(machine.id);
            machine.status = ns;
        }
        return machineRepository.save(machine);
    }

    /**
     * 班长报修：
     * 1. 从机台行锁进入，和并发的试装提交串行，最终以先提交者为准；
     * 2. 机名下有在开印（进行中）工单则报修不成功，点出是哪一张，先收尾或退回；
     * 3. 报修生效的同一刻，机台上仍停在未过的签样一并作废成退回，
     *    先前扣掉的试装纸不动（留在账外不还），和车间签样退回的做法一致；
     * 4. 机台状态落为维修。被作废的签样修好后不自动恢复，需重新开一笔试装。
     */
    @Transactional
    public Machine reportRepair(Long id) {
        Machine machine = machineRepository.findByIdForUpdate(id);
        if (machine == null) throw new BizException("机器不存在");

        List<Order> running = orderRepository.findRunningListByMachineIdForUpdate(id);
        if (!running.isEmpty()) {
            Order o = running.get(0);
            throw new BizException("该机台有工单正在开印：#" + o.id + " " + o.jobName
                    + "，请先把它收尾（完工）或退回（退回待排）后再报修");
        }

        List<Signoff> pending = signoffRepository.findPendingByMachineIdForUpdate(id);
        for (Signoff s : pending) {
            // 只改状态成退回；paperDeducted 原样保留，已扣的试装纸不还
            s.status = "退回";
            signoffRepository.save(s);
        }

        machine.status = MachineStatus.REPAIR;
        return machineRepository.save(machine);
    }

    /** 机台修好：只从维修回到空闲；被连带作废的签样不自动恢复，得重新开试装 */
    @Transactional
    public Machine restore(Long id) {
        Machine machine = machineRepository.findByIdForUpdate(id);
        if (machine == null) throw new BizException("机器不存在");
        if (!MachineStatus.REPAIR.equals(machine.status))
            throw new BizException("只有维修中的机器才能修好，当前状态：" + machine.status);
        machine.status = MachineStatus.IDLE;
        return machineRepository.save(machine);
    }

    /** 运行中的机器不能随手置空闲：在印工单会因此变成账外单，交班说不清 */
    private void blockIfRunningOrder(Long machineId) {
        List<Order> running = orderRepository.findRunningListByMachineIdForUpdate(machineId);
        if (!running.isEmpty()) {
            Order o = running.get(0);
            throw new BizException("该机台有工单正在开印：#" + o.id + " " + o.jobName
                    + "，请先把它收尾或退回，再把机台置为空闲");
        }
    }
}
