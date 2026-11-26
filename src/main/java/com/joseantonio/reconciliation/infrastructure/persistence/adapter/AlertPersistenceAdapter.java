package com.joseantonio.reconciliation.infrastructure.persistence.adapter;

import com.joseantonio.reconciliation.application.port.out.AuditAlertPort;
import com.joseantonio.reconciliation.application.port.out.ClaimedBatch;
import com.joseantonio.reconciliation.domain.model.AuditAlert;
import com.joseantonio.reconciliation.domain.model.BatchPhase;
import com.joseantonio.reconciliation.domain.model.Severity;
import com.joseantonio.reconciliation.infrastructure.persistence.BatchOwnershipGuard;
import com.joseantonio.reconciliation.infrastructure.persistence.SqlStatements;
import com.joseantonio.reconciliation.infrastructure.persistence.TimeConversions;
import com.joseantonio.reconciliation.infrastructure.persistence.repository.SpringDataAlertRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class AlertPersistenceAdapter implements AuditAlertPort {

    private static final int MAX_DESCRIPTION = 500;
    private static final int AUDIT_PROGRESS = 90;

    private final JdbcTemplate jdbc;
    private final BatchOwnershipGuard guard;
    private final SpringDataAlertRepository repository;

    public AlertPersistenceAdapter(JdbcTemplate jdbc, BatchOwnershipGuard guard, SpringDataAlertRepository repository) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.repository = repository;
    }

    @Override
    @Transactional
    public void saveAll(ClaimedBatch batch, List<AuditAlert> alerts) {
        guard.heartbeat(batch, BatchPhase.AUDIT, AUDIT_PROGRESS);
        if (alerts.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(SqlStatements.INSERT_ALERT_IF_ABSENT, alerts, alerts.size(), (ps, a) -> {
            String description = a.description().length() <= MAX_DESCRIPTION
                    ? a.description() : a.description().substring(0, MAX_DESCRIPTION);
            ps.setLong(1, a.batchId());
            if (a.ledgerTxId() == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, a.ledgerTxId());
            }
            ps.setString(3, a.reference().value());
            ps.setString(4, a.rule());
            ps.setString(5, a.severity().name());
            ps.setString(6, description);
            ps.setObject(7, TimeConversions.toUtc(a.createdAt()));
            // NOT EXISTS (same movement, same rule, any batch)
            if (a.ledgerTxId() == null) {
                ps.setNull(8, Types.BIGINT);
            } else {
                ps.setLong(8, a.ledgerTxId());
            }
            ps.setString(9, a.rule());
            // NOT EXISTS (same batch, same reference, same rule)
            ps.setLong(10, a.batchId());
            ps.setString(11, a.reference().value());
            ps.setString(12, a.rule());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Severity, Long> countBySeverity(long batchId) {
        Map<Severity, Long> counts = new EnumMap<>(Severity.class);
        for (Object[] row : repository.countGroupedBySeverity(batchId)) {
            counts.put(Severity.valueOf((String) row[0]), ((Number) row[1]).longValue());
        }
        return counts;
    }
}
