package com.bindery.shop.repository;

import com.bindery.shop.entity.Paper;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaperRepository extends JpaRepository<Paper, Long> {
    Paper findByCode(String code);

    /** 扣减库存用的当前读（SELECT ... FOR UPDATE），防并发超扣 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Paper p WHERE p.id = :id")
    Paper findByIdForUpdate(@Param("id") Long id);
}
