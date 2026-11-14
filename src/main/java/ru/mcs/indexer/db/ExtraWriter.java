package ru.mcs.indexer.db;

import ru.mcs.indexer.lang.FileFacts;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** Inserts java_modules / module_directives / config_entries of one file. Call inside a transaction. */
final class ExtraWriter implements AutoCloseable {

    private final PreparedStatement insertModule;
    private final PreparedStatement insertDirective;
    private final PreparedStatement insertConfig;
    private final PreparedStatement lastId;

    ExtraWriter(Db db) throws SQLException {
        Connection c = db.connection();
        insertModule = c.prepareStatement("INSERT INTO java_modules(file_id, name, is_open) VALUES (?,?,?)");
        insertDirective = c.prepareStatement("INSERT INTO module_directives(java_module_id, directive, target, "
                + "extra, line) VALUES (?,?,?,?,?)");
        insertConfig = c.prepareStatement("INSERT INTO config_entries(file_id, key_path, value, line) "
                + "VALUES (?,?,?,?)");
        lastId = c.prepareStatement("SELECT last_insert_rowid()");
    }

    void write(long fileId, FileFacts facts) throws SQLException {
        FileFacts.ModuleRow module = facts.javaModule();
        if (module != null) {
            insertModule.setLong(1, fileId);
            insertModule.setString(2, module.name());
            insertModule.setInt(3, module.open() ? 1 : 0);
            insertModule.executeUpdate();
            long moduleId = lastInsertId();
            for (FileFacts.DirectiveRow directive : facts.directives()) {
                insertDirective.setLong(1, moduleId);
                insertDirective.setString(2, directive.directive());
                insertDirective.setString(3, directive.target());
                if (directive.extra() == null) {
                    insertDirective.setNull(4, Types.VARCHAR);
                } else {
                    insertDirective.setString(4, directive.extra());
                }
                insertDirective.setInt(5, directive.line());
                insertDirective.addBatch();
            }
            insertDirective.executeBatch();
        }
        for (FileFacts.ConfigRow config : facts.configs()) {
            insertConfig.setLong(1, fileId);
            insertConfig.setString(2, config.keyPath());
            if (config.value() == null) {
                insertConfig.setNull(3, Types.VARCHAR);
            } else {
                insertConfig.setString(3, config.value());
            }
            insertConfig.setInt(4, config.line());
            insertConfig.addBatch();
        }
        insertConfig.executeBatch();
    }

    private long lastInsertId() throws SQLException {
        try (ResultSet rs = lastId.executeQuery()) {
            if (!rs.next()) {
                throw new SQLException("last_insert_rowid() returned no row");
            }
            return rs.getLong(1);
        }
    }

    @Override
    public void close() throws SQLException {
        for (PreparedStatement ps : new PreparedStatement[]{insertModule, insertDirective, insertConfig, lastId}) {
            ps.close();
        }
    }
}