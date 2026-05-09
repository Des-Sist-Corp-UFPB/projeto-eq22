package com.iwrite.item.repository;

import com.iwrite.item.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, UUID> {

    List<Item> findByBookIdOrderByNameAscIdAsc(UUID bookId);
}
