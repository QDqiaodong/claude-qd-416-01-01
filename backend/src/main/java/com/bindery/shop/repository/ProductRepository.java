package com.bindery.shop.repository;

import com.bindery.shop.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByOrderId(Long orderId);

    /**
     * 入库 / 换单统一从成品行锁开始（SELECT ... FOR UPDATE）：
     * 并发的入库、换单在同一行上串行，后到的等先提交的落库后再判定，
     * 不会两个人同时把同一笔待入库成品都办成已入库。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Product findByIdForUpdate(@Param("id") Long id);
}
