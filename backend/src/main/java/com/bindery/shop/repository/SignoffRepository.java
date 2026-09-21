package com.bindery.shop.repository;

import com.bindery.shop.entity.Signoff;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SignoffRepository extends JpaRepository<Signoff, Long> {
    List<Signoff> findByOrderId(Long orderId);

    /** 机台锁拿到之后复查签样行：报修若已先提交，这里立即读到“退回”，不能再按旧快照写 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Signoff s WHERE s.id = :id")
    Signoff findByIdForUpdate(@Param("id") Long id);

    /** 报修生效时连带作废用：该台机名下所有仍停在未过的签样（锁行当前读） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Signoff s WHERE s.machineId = :machineId AND s.status = '未过'")
    List<Signoff> findPendingByMachineIdForUpdate(@Param("machineId") Long machineId);

    /**
     * 门禁用的当前读（SELECT ... FOR UPDATE）：
     * 开印/建档必须看到最新提交的签样结果，并发退回一旦提交即按退回拦住，
     * 不能按事务开始前的“已过”快照放行。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Signoff s WHERE s.orderId = :orderId AND s.status = '已过'")
    List<Signoff> findPassedByOrderIdForUpdate(@Param("orderId") Long orderId);
}
