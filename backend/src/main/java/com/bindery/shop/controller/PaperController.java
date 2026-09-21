package com.bindery.shop.controller;

import com.bindery.shop.entity.Paper;
import com.bindery.shop.service.PaperService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/papers")
public class PaperController {
    @Autowired PaperService service;

    @GetMapping
    public List<Paper> list() {
        return service.list();
    }

    @PostMapping
    public Paper create(@RequestBody Map<String, Object> m) {
        return service.create(m);
    }

    @PutMapping("/{id}")
    public Paper update(@PathVariable Long id, @RequestBody Map<String, Object> m) {
        return service.update(id, m);
    }

    @PostMapping("/{id}/consume")
    public Paper consume(@PathVariable Long id, @RequestBody Map<String, Object> m) {
        return service.consume(id, m);
    }
}
