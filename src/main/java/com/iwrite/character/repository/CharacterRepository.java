package com.iwrite.character.repository;

import com.iwrite.character.entity.Character;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CharacterRepository extends JpaRepository<Character, UUID> {

    List<Character> findByBookIdOrderByNameAscIdAsc(UUID bookId);
}
