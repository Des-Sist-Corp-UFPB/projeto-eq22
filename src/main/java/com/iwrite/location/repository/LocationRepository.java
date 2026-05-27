package com.iwrite.location.repository;

import com.iwrite.location.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    List<Location> findByBookIdOrderByNameAscIdAsc(UUID bookId);
}
