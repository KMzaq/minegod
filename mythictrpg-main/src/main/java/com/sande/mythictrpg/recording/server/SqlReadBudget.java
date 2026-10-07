package com.sande.mythictrpg.recording.server;

import java.sql.Connection;
import java.sql.SQLException;
import org.sqlite.ProgressHandler;

/** Interrupts SQLite VM work, not just lock acquisition. A JDBC query timeout alone does not bound a scan. */
final class SqlReadBudget implements AutoCloseable {
    private final Connection db;
    SqlReadBudget(Connection db, long deadline) throws SQLException {
        this.db = db;
        ProgressHandler.setHandler(db, 1000, new ProgressHandler() {
            @Override protected int progress() { return System.nanoTime() >= deadline || Thread.currentThread().isInterrupted() ? 1 : 0; }
        });
    }
    @Override public void close() throws SQLException { ProgressHandler.clearHandler(db); }
}
