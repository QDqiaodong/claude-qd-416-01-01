package com.bindery.shop.controller;

import com.bindery.shop.entity.Signoff;
import com.bindery.shop.service.SignoffService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/signoffs")
public class SignoffController {
    @Autowired SignoffService service;

    @GetMapping
    public List<Signoff> list(@RequestParam(required = false) Long orderId) {
        return service.list(orderId);
    }

    @PostMapping
    public Signoff create(@RequestBody Map<String, Object> m) {
        return service.create(m);
    }

    @PutMapping("/{id}")
    public Signoff update(@PathVariable Long id, @RequestBody Map<String, Object> m) {
        return service.update(id, m);
    }
}
