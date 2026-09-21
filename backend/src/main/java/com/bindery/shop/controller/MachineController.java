package com.bindery.shop.controller;

import com.bindery.shop.entity.Machine;
import com.bindery.shop.service.MachineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/machines")
public class MachineController {
    @Autowired MachineService service;

    @GetMapping
    public List<Machine> list(@RequestParam(required = false) String type,
                              @RequestParam(required = false) String status) {
        return service.list(type, status);
    }

    @PostMapping
    public Machine create(@RequestBody Map<String, Object> m) {
        return service.create(m);
    }

    @PutMapping("/{id}")
    public Machine update(@PathVariable Long id, @RequestBody Map<String, Object> m) {
        return service.update(id, m);
    }

    /** 班长报修：挡在印工单，连带作废未过签样（已扣试装纸不还），机台落维修 */
    @PostMapping("/{id}/repair")
    public Machine repair(@PathVariable Long id) {
        return service.reportRepair(id);
    }

    /** 机台修好：回到空闲（被作废的签样不恢复，需重新开试装） */
    @PostMapping("/{id}/restore")
    public Machine restore(@PathVariable Long id) {
        return service.restore(id);
    }
}
