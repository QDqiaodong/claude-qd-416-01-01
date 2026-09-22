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
     * 入库 / 换单 / 已入库锁定校验用的当前读（SELECT ... FOR UPDATE）：
     * 先锁成品行再锁目标工单和签样行，两个并发入库同一笔成品时后来者在本行排队，
     * 等先提交者落“已入库”后再读到最终状态，不会重复入库。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Product findByIdForUpdate(@Param("id") Long id);

    /**
     * 只取当前归属工单号的标量查询：避免先把成品实体装进 Hibernate 一级缓存，
     * 否则随后的 findByIdForUpdate 会命中缓存旧快照，行锁当前读就白做了
     * （并发入库时后来者可能读不到先提交的“已入库”）。
     */
    @Query("SELECT p.orderId FROM Product p WHERE p.id = :id")
    Long findOrderIdById(@Param("id") Long id);

    /**
     * 签样退回门禁用的当前读（SELECT ... FOR UPDATE）：
     * 必须用当前读而不是普通读——InnoDB 在 REPEATABLE READ 下普通读走事务快照，
     * 本事务等签样行锁期间别的事务可能刚把成品换过来，快照里看不到；
     * 当前读在锁等结束后按最新提交版本评估，保证“退回提交时工单上确实没有成品”。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.orderId = :orderId")
    List<Product> findByOrderIdForUpdate(@Param("orderId") Long orderId);
}
