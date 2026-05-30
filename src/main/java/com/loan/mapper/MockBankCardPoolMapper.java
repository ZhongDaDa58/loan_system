package com.loan.mapper;

import com.loan.entity.MockBankCardPool;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;

public interface MockBankCardPoolMapper {
    MockBankCardPool selectByCardNumber(@Param("cardNumber") String cardNumber);
    MockBankCardPool selectById(@Param("id") Integer id);
    int decreaseBalance(@Param("id") Integer id, @Param("amount") BigDecimal amount);
    int increaseBalance(@Param("id") Integer id, @Param("amount") BigDecimal amount);
}
