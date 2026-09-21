package com.bindery.shop.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "machine")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "machine_type", discriminatorType = DiscriminatorType.STRING)
public class Machine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String code;
    public String name;
    public String status;

    public String getMachineType() {
        if (this instanceof GlueBindMachine) return "glue";
        if (this instanceof SaddleStitchMachine) return "saddle";
        return "machine";
    }
}
