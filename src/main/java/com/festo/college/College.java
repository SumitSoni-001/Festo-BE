package com.festo.college;

import com.festo.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class College extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "email_domain", nullable = false, unique = true)
    private String emailDomain;

    private String city;
    private String state;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
