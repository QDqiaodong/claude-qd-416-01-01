package com.bindery.shop.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "product")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long orderId;
    /** 换单前的上一张归属工单：待入库换单时落痕；已入库后与归属工单一起锁住不再变 */
    public Long previousOrderId;
    public String name;
    public Integer qty;
    public String status;
}
