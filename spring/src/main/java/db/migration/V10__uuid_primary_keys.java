package db.migration;


import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * E5: UUID primary keys (SDS 2.2, "All entity primary keys use UUID type") for every live operational table, and
 * UUID foreign keys for every relationship between them. Data-preserving and transactional:
 * <ol>
 *   <li>records row counts of the live tables and the legacy archives ({@code e5_migration_audit});</li>
 *   <li>assigns each existing row a UUID and records old BIGINT id → UUID in {@code legacy_id_map};</li>
 *   <li>fills UUID foreign-key columns through that map;</li>
 *   <li>replaces keys, constraints and indexes (the V1 foreign keys were unnamed, so their names are read from
 *       information_schema; the same code runs on PostgreSQL and on H2 in tests);</li>
 *   <li>verifies counts, mappings, column types, constraints and the one-active-booking index, and throws on any
 *       mismatch so the whole migration rolls back.</li>
 * </ol>
 * The legacy_* archives keep their BIGINT ids; they stay joinable through {@code legacy_id_map}.
 */
public class V10__uuid_primary_keys extends BaseJavaMigration {

    /** Parents before children. */
    static final List<String> TABLES = List.of("users", "scooters", "gear_items", "bookings", "booking_gear",
            "contracts", "payments", "payment_transactions", "invoices", "invoice_lines", "notifications",
            "maintenance_records", "gps_pings", "speed_violations");

    /** New UUID primary key column per table. Must match each entity's @Id @Column(name = ...). */
    static final Map<String, String> PK = Map.ofEntries(
            Map.entry("users", "user_id"),
            Map.entry("scooters", "scooter_id"),
            Map.entry("gear_items", "gear_item_id"),
            Map.entry("bookings", "booking_id"),
            Map.entry("booking_gear", "booking_gear_id"),
            Map.entry("contracts", "contract_id"),
            Map.entry("payments", "payment_id"),
            Map.entry("payment_transactions", "payment_transaction_id"),
            Map.entry("invoices", "invoice_id"),
            Map.entry("invoice_lines", "invoice_line_id"),
            Map.entry("notifications", "notification_id"),
            Map.entry("maintenance_records", "maintenance_record_id"),
            Map.entry("gps_pings", "gps_ping_id"),
            Map.entry("speed_violations", "speed_violation_id"),
            Map.entry("gps_logs", "gps_log_id")
    );

    static final List<String> ARCHIVES = List.of("legacy_payments", "legacy_booking_terms",
            "legacy_booking_gear_rates", "legacy_scooter_rates", "legacy_booking_pickup_missing",
            "legacy_gps_pings_unlinked", "legacy_gps_ping_precision");

    record Fk(String table, String column, String parent, boolean notNull, boolean cascade) {
        String name() {
            return "fk_" + table + "_" + column;
        }
    }

    static final List<Fk> FKS = List.of(
            new Fk("bookings", "customer_id", "users", true, false),
            new Fk("bookings", "scooter_id", "scooters", true, false),
            new Fk("booking_gear", "booking_id", "bookings", true, true),
            new Fk("booking_gear", "gear_item_id", "gear_items", true, false),
            new Fk("contracts", "booking_id", "bookings", true, true),
            new Fk("payments", "booking_id", "bookings", true, false),
            new Fk("payment_transactions", "payment_id", "payments", true, false),
            new Fk("invoices", "booking_id", "bookings", true, false),
            new Fk("invoice_lines", "invoice_id", "invoices", true, true),
            new Fk("maintenance_records", "scooter_id", "scooters", true, false),
            new Fk("notifications", "user_id", "users", true, false),
            new Fk("gps_pings", "scooter_id", "scooters", true, false),
            new Fk("gps_pings", "booking_id", "bookings", true, false),
            new Fk("speed_violations", "scooter_id", "scooters", true, false),
            new Fk("speed_violations", "booking_id", "bookings", false, false));

    /** Indexes on key columns, recreated with their original names and columns. */
    static final Map<String, String> INDEXES = new LinkedHashMap<>();

    static {
        INDEXES.put("idx_bookings_customer", "bookings (customer_id)");
        INDEXES.put("idx_bookings_scooter_status", "bookings (scooter_id, status)");
        INDEXES.put("idx_payments_booking", "payments (booking_id)");
        INDEXES.put("idx_notifications_user", "notifications (user_id)");
        INDEXES.put("idx_gps_pings_scooter_time", "gps_pings (scooter_id, recorded_at)");
        INDEXES.put("idx_gps_pings_booking_time", "gps_pings (booking_id, recorded_at)");
        INDEXES.put("idx_payment_tx_payment", "payment_transactions (payment_id, created_at)");
    }

    /** Single-column UNIQUE constraints on foreign keys (one contract and one payment per booking). */
    static final Map<String, String> UNIQUES = Map.of(
            "uq_contracts_booking", "contracts (booking_id)",
            "uq_payments_booking", "payments (booking_id)");

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        boolean postgres = c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");

        // 1. Counts before.
        exec(c, "CREATE TABLE e5_migration_audit (table_name VARCHAR(64) PRIMARY KEY, rows_before BIGINT NOT NULL, "
                + "rows_after BIGINT)");
        for (String t : concat(TABLES, ARCHIVES)) {
            exec(c, "INSERT INTO e5_migration_audit (table_name, rows_before) VALUES ('" + t + "', " + count(c, t) + ")");
        }

        // Invoice lines were ordered by their BIGINT id; keep that order explicitly before the ids change.
        exec(c, "ALTER TABLE invoice_lines ADD COLUMN line_no INTEGER");
        exec(c, "UPDATE invoice_lines SET line_no = (SELECT COUNT(*) FROM invoice_lines o "
                + "WHERE o.invoice_id = invoice_lines.invoice_id AND o.id < invoice_lines.id)");
        exec(c, "ALTER TABLE invoice_lines ALTER COLUMN line_no SET NOT NULL");

        // 2. A UUID for every existing row, recorded in the old-id map.
        exec(c, "CREATE TABLE legacy_id_map (table_name VARCHAR(64) NOT NULL, old_id BIGINT NOT NULL, "
                + "new_id UUID NOT NULL, PRIMARY KEY (table_name, old_id), CONSTRAINT uq_legacy_id_map_new UNIQUE (new_id))");
        for (String t : TABLES) {
            List<Long> ids = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id FROM " + t)) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO legacy_id_map (table_name, old_id, new_id) VALUES (?, ?, ?)")) {
                for (Long id : ids) {
                    ps.setString(1, t);
                    ps.setLong(2, id);
                    ps.setObject(3, UUID.randomUUID());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            exec(c, "ALTER TABLE " + t + " ADD COLUMN e5_uuid UUID");
            exec(c, "UPDATE " + t + " SET e5_uuid = (SELECT m.new_id FROM legacy_id_map m WHERE m.table_name = '" + t
                    + "' AND m.old_id = " + t + ".id)");
            requireZero(c, "SELECT COUNT(*) FROM " + t + " WHERE e5_uuid IS NULL", t + ": rows without a UUID");
        }

        // 3. UUID foreign keys through the map.
        for (Fk fk : FKS) {
            exec(c, "ALTER TABLE " + fk.table() + " ADD COLUMN " + fk.column() + "_uid UUID");
            exec(c, "UPDATE " + fk.table() + " SET " + fk.column() + "_uid = (SELECT m.new_id FROM legacy_id_map m "
                    + "WHERE m.table_name = '" + fk.parent() + "' AND m.old_id = " + fk.table() + "." + fk.column() + ")");
            requireZero(c, "SELECT COUNT(*) FROM " + fk.table() + " WHERE " + fk.column() + " IS NOT NULL AND "
                    + fk.column() + "_uid IS NULL", fk.table() + "." + fk.column() + ": unmapped references");
        }

        // 4. Remove old keys, constraints and indexes on key columns.
        exec(c, "DROP INDEX IF EXISTS ux_bookings_one_active_per_scooter");
        if (!postgres && columnExists(c, "bookings", "active_scooter_id")) {
            exec(c, "ALTER TABLE bookings DROP COLUMN active_scooter_id"); // H2 stand-in for the partial index
        }
        for (String[] k : constraints(c, "FOREIGN KEY")) {
            exec(c, "ALTER TABLE " + k[0] + " DROP CONSTRAINT " + k[1]);
        }
        for (String[] k : constraints(c, "UNIQUE")) {
            if (isKeyColumn(k[0], k[2])) {
                exec(c, "ALTER TABLE " + k[0] + " DROP CONSTRAINT " + k[1]);
            }
        }
        for (String[] k : constraints(c, "PRIMARY KEY")) {
            exec(c, "ALTER TABLE " + k[0] + " DROP CONSTRAINT " + k[1]);
        }
        for (String index : INDEXES.keySet()) {
            exec(c, "DROP INDEX IF EXISTS " + index);
        }

        // 5. Swap the columns.
        for (Fk fk : FKS) {
            exec(c, "ALTER TABLE " + fk.table() + " DROP COLUMN " + fk.column());
            exec(c, "ALTER TABLE " + fk.table() + " RENAME COLUMN " + fk.column() + "_uid TO " + fk.column());
            if (fk.notNull()) {
                exec(c, "ALTER TABLE " + fk.table() + " ALTER COLUMN " + fk.column() + " SET NOT NULL");
            }
        }
        for (String t : TABLES) {
            exec(c, "ALTER TABLE " + t + " DROP COLUMN id");
            exec(c, "ALTER TABLE " + t + " RENAME COLUMN e5_uuid TO id");
            exec(c, "ALTER TABLE " + t + " ALTER COLUMN id SET NOT NULL");
            exec(c, "ALTER TABLE " + t + " ADD CONSTRAINT pk_" + t + " PRIMARY KEY (id)");
        }

        // 6. Constraints and indexes back, with explicit names.
        for (Fk fk : FKS) {
            exec(c, "ALTER TABLE " + fk.table() + " ADD CONSTRAINT " + fk.name() + " FOREIGN KEY (" + fk.column()
                    + ") REFERENCES " + fk.parent() + " (id)" + (fk.cascade() ? " ON DELETE CASCADE" : ""));
        }
        for (var u : UNIQUES.entrySet()) {
            String table = u.getValue().substring(0, u.getValue().indexOf(' '));
            exec(c, "ALTER TABLE " + table + " ADD CONSTRAINT " + u.getKey() + " UNIQUE " + u.getValue().substring(table.length()));
        }
        for (var i : INDEXES.entrySet()) {
            exec(c, "CREATE INDEX " + i.getKey() + " ON " + i.getValue());
        }
        if (postgres) {
            exec(c, "CREATE UNIQUE INDEX ux_bookings_one_active_per_scooter ON bookings (scooter_id) WHERE status = 'ACTIVE'");
        } else {
            exec(c, "ALTER TABLE bookings ADD COLUMN active_scooter_id UUID GENERATED ALWAYS AS "
                    + "(CASE WHEN status = 'ACTIVE' THEN scooter_id END)");
            exec(c, "CREATE UNIQUE INDEX ux_bookings_one_active_per_scooter ON bookings (active_scooter_id)");
        }

        // 7. Verify, or fail (and roll back).
        for (String t : concat(TABLES, ARCHIVES)) {
            exec(c, "UPDATE e5_migration_audit SET rows_after = " + count(c, t) + " WHERE table_name = '" + t + "'");
        }
        requireZero(c, "SELECT COUNT(*) FROM e5_migration_audit WHERE rows_after <> rows_before",
                "row counts changed");
        for (String t : TABLES) {
            requireZero(c, "SELECT (SELECT COUNT(*) FROM " + t + ") - (SELECT COUNT(*) FROM legacy_id_map WHERE table_name = '"
                    + t + "')", t + ": mapping count differs from row count");
            requireUuid(c, t, "id");
        }
        for (Fk fk : FKS) {
            requireUuid(c, fk.table(), fk.column());
            requireZero(c, "SELECT COUNT(*) FROM " + fk.table() + " x WHERE x." + fk.column() + " IS NOT NULL AND NOT EXISTS "
                            + "(SELECT 1 FROM " + fk.parent() + " p WHERE p.id = x." + fk.column() + ")",
                    fk.table() + "." + fk.column() + ": dangling reference");
        }
        int fkCount = constraints(c, "FOREIGN KEY").size();
        if (fkCount != FKS.size()) {
            throw new IllegalStateException("E5: expected " + FKS.size() + " foreign keys, found " + fkCount);
        }
        if (constraints(c, "PRIMARY KEY").size() != TABLES.size()) {
            throw new IllegalStateException("E5: a primary key is missing");
        }
        if (!indexExists(c, "ux_bookings_one_active_per_scooter")) {
            throw new IllegalStateException("E5: ux_bookings_one_active_per_scooter was not recreated");
        }
    }

    // ---------------------------------------------------------------- helpers

    static boolean isKeyColumn(String table, String column) {
        return column.equals("id") || FKS.stream().anyMatch(f -> f.table().equals(table) && f.column().equals(column));
    }

    /** [table, constraint, column] for constraints of the given type on the live tables. */
    static List<String[]> constraints(Connection c, String type) throws SQLException {
        String sql = "SELECT LOWER(tc.table_name), tc.constraint_name, LOWER(kcu.column_name) "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu ON kcu.constraint_name = tc.constraint_name "
                + "AND kcu.table_schema = tc.table_schema AND kcu.table_name = tc.table_name "
                + "WHERE LOWER(tc.table_schema) = 'public' AND tc.constraint_type = ?";
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (TABLES.contains(rs.getString(1))) {
                        out.add(new String[] {rs.getString(1), quote(rs.getString(2)), rs.getString(3)});
                    }
                }
            }
        }
        return out;
    }

    static boolean columnExists(Connection c, String table, String column) throws SQLException {
        return count(c, "information_schema.columns WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = '"
                + table + "' AND LOWER(column_name) = '" + column + "'") > 0;
    }

    static boolean indexExists(Connection c, String name) throws SQLException {
        try (ResultSet rs = c.getMetaData().getIndexInfo(null, null, "bookings", true, false)) {
            while (rs.next()) {
                if (name.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    static void requireUuid(Connection c, String table, String column) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT LOWER(data_type) FROM information_schema.columns WHERE LOWER(table_schema) = 'public' "
                        + "AND LOWER(table_name) = '" + table + "' AND LOWER(column_name) = '" + column + "'")) {
            if (!rs.next() || !rs.getString(1).equals("uuid")) {
                throw new IllegalStateException("E5: " + table + "." + column + " is not UUID");
            }
        }
    }

    static void requireZero(Connection c, String sql, String what) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            long n = rs.getLong(1);
            if (n != 0) {
                throw new IllegalStateException("E5 verification failed: " + what + " (" + n + ")");
            }
        }
    }

    static long count(Connection c, String tableOrFromClause) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + tableOrFromClause)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** Constraint names read from the catalog may need quoting (H2 generates upper-case names). */
    static String quote(String name) {
        return name.equals(name.toLowerCase()) ? name : "\"" + name + "\"";
    }

    static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}

