package com.bindery.shop.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "paper")
public class Paper {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String code;
    public Integer gsm;
    public Integer stock;
    public Integer warnLine;
}
