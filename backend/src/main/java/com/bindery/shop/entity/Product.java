package com.bindery.shop.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "product")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long orderId;
    /** 换单留痕：上一次归属的工单（仅从待入库换单时写入，已入库后不再变） */
    public Long prevOrderId;
    public String name;
    public Integer qty;
    /** 待入库 / 已入库（只能 待入库 → 已入库，不能跳过或倒退） */
    public String status;
}
