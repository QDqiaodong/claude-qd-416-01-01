package com.bindery.shop.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "trial_signoff")
public class Signoff {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    /** 挂哪张待排工单 */
    public Long orderId;
    /** 用哪台装订机试装 */
    public Long machineId;
    /** 抽哪一种纸试装 */
    public Long paperId;
    /** 试装几册（试装用纸按册数从纸张库存扣） */
    public Integer trialQty;
    /** 书脊厚度 mm（胶装机试装必填） */
    public Integer spineThickness;
    /** 订针数（骑马钉机试装必填） */
    public Integer stitchCount;
    /** 试装纸是否已从库存扣减（退回不退纸） */
    public Boolean paperDeducted;
    /** 未过 / 已过 / 退回 */
    public String status;
}
