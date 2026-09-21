package com.bindery.shop.controller;

import com.bindery.shop.entity.Order;
import com.bindery.shop.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    @Autowired OrderService service;

    @GetMapping
    public List<Order> list() {
        return service.list();
    }

    @PostMapping
    public Order create(@RequestBody Map<String, Object> m) {
        return service.create(m);
    }

    @PutMapping("/{id}")
    public Order update(@PathVariable Long id, @RequestBody Map<String, Object> m) {
        return service.update(id, m);
    }
}
