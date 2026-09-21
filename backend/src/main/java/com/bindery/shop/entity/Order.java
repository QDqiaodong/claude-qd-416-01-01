package com.bindery.shop.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "bind_order")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long machineId;
    public String jobName;
    public Integer qty;
    public String status;
}
