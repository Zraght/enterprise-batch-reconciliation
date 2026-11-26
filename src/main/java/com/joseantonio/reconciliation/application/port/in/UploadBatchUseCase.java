package com.joseantonio.reconciliation.application.port.in;

public interface UploadBatchUseCase {

    UploadResult upload(UploadBatchCommand command);
}
