package com.iwrite.user.repository;

import com.iwrite.user.entity.UserPersona;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserPersonaRepository extends JpaRepository<UserPersona, UUID> {
}
