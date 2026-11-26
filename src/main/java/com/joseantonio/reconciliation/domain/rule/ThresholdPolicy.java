package com.joseantonio.reconciliation.domain.rule;

import com.joseantonio.reconciliation.domain.model.AccountType;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;

/** Corporate limits per account type and currency. A missing limit means "no limit configured". */
public record ThresholdPolicy(Map<AccountType, Map<Currency, BigDecimal>> limits) {

    public ThresholdPolicy {
        limits = limits == null ? Map.of() : Map.copyOf(limits);
    }

    public Optional<BigDecimal> limitFor(AccountType type, Currency currency) {
        return Optional.ofNullable(limits.get(type)).map(m -> m.get(currency));
    }
}
