package com.loan.entity.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UserRegisterDTOTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void testUserRegisterDTOSetterGetter() {
        UserRegisterDTO dto = new UserRegisterDTO();

        dto.setPhone("13812345678");
        dto.setPassword("abc123");
        dto.setRealName("张三");

        assertEquals("13812345678", dto.getPhone());
        assertEquals("abc123", dto.getPassword());
        assertEquals("张三", dto.getRealName());
    }

    @Test
    void testUserRegisterDTOValidationSuccess() {
        UserRegisterDTO dto = new UserRegisterDTO();

        dto.setPhone("13812345678");
        dto.setPassword("abc123");
        dto.setRealName("张三");

        Set<ConstraintViolation<UserRegisterDTO>> violations = validator.validate(dto);
        assertTrue(violations.isEmpty());
    }

    @Test
    void testUserRegisterDTOInvalidPhone() {
        UserRegisterDTO dto = new UserRegisterDTO();

        dto.setPhone("12345");  // 无效手机号
        dto.setPassword("abc123");
        dto.setRealName("张三");

        Set<ConstraintViolation<UserRegisterDTO>> violations = validator.validate(dto);
        assertFalse(violations.isEmpty());
    }
}

