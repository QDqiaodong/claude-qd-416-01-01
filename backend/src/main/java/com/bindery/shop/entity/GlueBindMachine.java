package com.bindery.shop.entity;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("glue")
public class GlueBindMachine extends Machine {
    public Integer maxThickness;
}
