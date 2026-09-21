package com.bindery.shop.repository;

import com.bindery.shop.entity.Machine;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MachineRepository extends JpaRepository<Machine, Long> {
    Machine findByCode(String code);

    /**
     * 报修 / 开试装 / 开印统一从机台行锁开始，按同一把锁串行：
     * 谁先提交谁拿到锁，后来的事务在同一行上等待，
     * 等前一个提交后再读到最终状态，不会两边各按自己看到的状态各写一笔。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Machine m WHERE m.id = :id")
    Machine findByIdForUpdate(@Param("id") Long id);
}
