package com.pokeranalytics.api.dto;

import java.math.BigDecimal;

/** docs/10-api-design.md's money-as-a-string rule ("never a JSON number, to avoid float rounding
 *  on summed figures") — every money-valued field in this API uses this shape. */
public record MoneyDto(String amount, String currency) {

    public static MoneyDto of(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        return new MoneyDto(amount.toPlainString(), currency);
    }
}
