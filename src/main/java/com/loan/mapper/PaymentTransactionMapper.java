package com.loan.mapper;

import com.loan.entity.PaymentTransaction;
import org.apache.ibatis.annotations.Param;

public interface PaymentTransactionMapper {
    int insert(PaymentTransaction transaction);
    int updateStatus(@Param("transactionId") String transactionId,
                     @Param("status") String status,
                     @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage,
                     @Param("balanceBefore") java.math.BigDecimal balanceBefore,
                     @Param("balanceAfter") java.math.BigDecimal balanceAfter);
    PaymentTransaction selectByTransactionId(@Param("transactionId") String transactionId);
}
