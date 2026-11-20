package ru.mcs.mcpsql.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

final class Jdbc {

    @FunctionalInterface
    interface RowSink {
        /** Returns false to stop reading. */
        boolean accept(ResultSet rs) throws SQLException;
    }

    private Jdbc() {
    }

    static void query(Connection c, String sql, RowSink sink, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!sink.accept(rs)) {
                        break;
                    }
                }
            }
        }
    }

    static String one(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** Header plus tab-separated rows; newlines inside values become the two characters \n. */
    static int table(ResultSet rs, Budget budget, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int columns = md.getColumnCount();
        StringBuilder header = new StringBuilder();
        for (int i = 1; i <= columns; i++) {
            if (i > 1) {
                header.append('\t');
            }
            header.append(md.getColumnLabel(i));
        }
        budget.line(header.toString());
        int rows = 0;
        while (rs.next()) {
            if (rows >= maxRows) {
                budget.line("... [more rows exist; showing the first " + maxRows + "]");
                break;
            }
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= columns; i++) {
                if (i > 1) {
                    row.append('\t');
                }
                row.append(cell(rs.getString(i)));
            }
            if (!budget.line(row.toString())) {
                break;
            }
            rows++;
        }
        return rows;
    }

    static String cell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", "").replace("\t", " ").replace("\n", "\\n");
    }
}