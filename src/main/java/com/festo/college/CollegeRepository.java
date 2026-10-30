package com.festo.college;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollegeRepository extends JpaRepository<College, Long> {
    Optional<College> findByEmailDomain(String emailDomain);
}
