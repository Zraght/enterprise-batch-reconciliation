package com.joseantonio.reconciliation.application.port.out;

import com.joseantonio.reconciliation.domain.model.FileFormat;

public interface SettlementFileReaderPort {

    RecordSource open(String location, FileFormat format);
}
