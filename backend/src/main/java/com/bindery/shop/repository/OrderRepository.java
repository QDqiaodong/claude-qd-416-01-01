package com.bindery.shop.repository;

import com.bindery.shop.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    /** 报修 / 开印前的在印校验：锁机台行之后再当前读，保证并发下看到的是最新在印结果 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.machineId = :machineId AND o.status = '进行中'")
    List<Order> findRunningListByMachineIdForUpdate(@Param("machineId") Long machineId);

    /**
     * 成品建档 / 入库 / 换单入口用的当前读（SELECT ... FOR UPDATE）：
     * 锁目标工单行后再锁该工单的已过签样行，使换单/入库与并发签样退回严格串行。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Order findByIdForUpdate(@Param("id") Long id);

    List<Order> findByMachineId(Long machineId);
}
