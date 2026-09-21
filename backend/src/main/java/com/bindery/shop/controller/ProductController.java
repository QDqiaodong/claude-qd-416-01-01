package com.bindery.shop.controller;

import com.bindery.shop.entity.Product;
import com.bindery.shop.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    @Autowired ProductService service;

    @GetMapping
    public List<Product> list() {
        return service.list();
    }

    @PostMapping
    public Product create(@RequestBody Map<String, Object> m) {
        return service.create(m);
    }

    @PutMapping("/{id}")
    public Product update(@PathVariable Long id, @RequestBody Map<String, Object> m) {
        return service.update(id, m);
    }
}
