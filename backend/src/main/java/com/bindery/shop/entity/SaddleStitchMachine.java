package com.bindery.shop.entity;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("saddle")
public class SaddleStitchMachine extends Machine {
    public Integer maxStitches;
}
