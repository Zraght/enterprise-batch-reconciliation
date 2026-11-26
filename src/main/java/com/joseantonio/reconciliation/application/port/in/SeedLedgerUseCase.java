package com.joseantonio.reconciliation.application.port.in;

public interface SeedLedgerUseCase {

    SeedResult seed(SeedCommand command);
}
