package com.bindery.shop.service;

import com.bindery.shop.dto.BizException;
import com.bindery.shop.entity.*;
import com.bindery.shop.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PaperService {
    @Autowired PaperRepository paperRepository;

    public List<Paper> list() {
        return paperRepository.findAll();
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

    public Paper create(Map<String, Object> m) {
        String code = str(m, "code");
        if (code == null || code.isBlank()) throw new BizException("纸张编号必填");
        if (paperRepository.findByCode(code) != null) throw new BizException("纸张编号 " + code + " 已存在");
        Paper p = new Paper();
        p.code = code;
        p.gsm = intV(m, "gsm");
        p.stock = intV(m, "stock") == null ? 0 : intV(m, "stock");
        p.warnLine = intV(m, "warnLine") == null ? 0 : intV(m, "warnLine");
        return paperRepository.save(p);
    }

    public Paper update(Long id, Map<String, Object> m) {
        Paper p = paperRepository.findById(id).orElseThrow(() -> new BizException("纸张不存在"));
        if (m.containsKey("code")) {
            String code = str(m, "code");
            if (code == null || code.isBlank()) throw new BizException("纸张编号必填");
            Paper dup = paperRepository.findByCode(code);
            if (dup != null && !dup.id.equals(id)) throw new BizException("纸张编号 " + code + " 已存在");
            p.code = code;
        }
        if (m.containsKey("gsm")) p.gsm = intV(m, "gsm");
        if (m.containsKey("stock")) p.stock = intV(m, "stock") == null ? 0 : intV(m, "stock");
        if (m.containsKey("warnLine")) p.warnLine = intV(m, "warnLine") == null ? 0 : intV(m, "warnLine");
        return paperRepository.save(p);
    }

    public Paper consume(Long id, Map<String, Object> m) {
        Paper p = paperRepository.findById(id).orElseThrow(() -> new BizException("纸张不存在"));
        Integer qty = intV(m, "qty");
        if (qty == null || qty <= 0) throw new BizException("领纸数量必须大于 0");
        if (p.stock < qty) throw new BizException("库存不足，无法领纸（当前库存 " + p.stock + " 张）");
        p.stock -= qty;
        return paperRepository.save(p);
    }
}
