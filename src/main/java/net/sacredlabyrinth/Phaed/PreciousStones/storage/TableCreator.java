package net.sacredlabyrinth.Phaed.PreciousStones.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import net.sacredlabyrinth.Phaed.PreciousStones.PreciousStones;
import net.sacredlabyrinth.Phaed.PreciousStones.helpers.Helper;

class TableCreator implements AutoCloseable {

    private final DBCore core;
    private final Connection conn;
    private final boolean isMySql;
    
    private boolean addedIndexes;
    
    TableCreator(DBCore core) throws SQLException {
        this.core = core;
        conn = core.getConnection();
        isMySql = core.getVendorType() == VendorType.MYSQL;
    }
    
    void createTables() throws SQLException {
        createCuboids();
        createFields();
        createUnbreakables();
        createGriefUndo();
        createTranslocations();
        createStoredBlocks();
        createPlayers();
        createSnitches();
        createPurchasePayments();
    }
    
    boolean tableExists(String table) throws SQLException {
        return core.tableExists(conn, table);
    }
    
    private void execute(String statement) throws SQLException {
        try (PreparedStatement prepStmt = conn.prepareStatement(statement)) {
            prepStmt.execute();
        }
    }
    
    private void execute(String statement, Object param1) throws SQLException {
        try (PreparedStatement prepStmt = conn.prepareStatement(statement)) {
            prepStmt.setObject(1, param1);
            prepStmt.execute();
        }
    }
    
    private void createCuboids() throws SQLException {
        execute(
                "CREATE TABLE IF NOT EXISTS `pstone_cuboids` ("
                + ((isMySql) ? "`id` bigint(20) NOT NULL auto_increment PRIMARY KEY" : "`id` INTEGER PRIMARY KEY") + ", "

                + "`parent` bigint(20) NOT NULL, "
                + "`x` int(11) default NULL, "
                + "`y` int(11) default NULL, "
                + "`z` int(11) default NULL, "

                + "`world` varchar(25) default NULL, "
                + "`minx` int(11) default NULL, "
                + "`maxx` int(11) default NULL, "
                + "`miny` int(11) default NULL, "
                + "`maxy` int(11) default NULL, "
                + "`minz` int(11) default NULL, "
                + "`maxz` int(11) default NULL, "

                + "`velocity` float default NULL, "
                + "`type_id` int(11) default NULL, "
                + "`type` varchar(64) default NULL, "
                + "`data` tinyint default 0, "
                + "`owner` varchar(16) NOT NULL, "
                + "`name` varchar(50) NOT NULL, "
                + "`packed_allowed` text NOT NULL, "
                + "`last_used` bigint(20) Default NULL, "
                + "`flags` TEXT NOT NULL, "
                + ((isMySql) ?
                        "UNIQUE KEY `uq_cuboid_fields_1` (`x`,`y`,`z`,`world`))"
                        : "UNIQUE (`x`,`y`,`z`,`world`))"));
    }
    
    private void createFields() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS `pstone_fields` ("
                + ((isMySql) ? "`id` bigint(20) NOT NULL auto_increment PRIMARY KEY" : "`id` INTEGER PRIMARY KEY") + ", "

                + "`x` int(11) default NULL, "
                + "`y` int(11) default NULL, "
                + "`z` int(11) default NULL, "

                + "`world` varchar(25) default NULL, "
                + "`radius` int(11) default NULL, "
                + "`height` int(11) default NULL, "
                + "`velocity` float default NULL, "
                + "`type_id` int(11) default NULL, "
                + "`type` varchar(64) default NULL, "

                + "`data` tinyint default 0, "
                + "`owner` varchar(16) NOT NULL, "
                + "`name` varchar(50) NOT NULL, "
                + "`packed_allowed` text NOT NULL, "
                + "`last_used` bigint(20) Default NULL, "
                + "`flags` TEXT NOT NULL, "
                + ((isMySql) ?
                        "UNIQUE KEY `uq_pstone_fields_1` (`x`,`y`,`z`,`world`))"
                        : "UNIQUE (`x`,`y`,`z`,`world`))"));
    }
    
    private void createUnbreakables() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS `pstone_unbreakables` ("
                + ((isMySql) ? "`id` bigint(20) NOT NULL auto_increment PRIMARY KEY" : "`id` INTEGER PRIMARY KEY") + ", "
                + "`x` int(11) default NULL, "
                + "`y` int(11) default NULL, "
                + "`z` int(11) default NULL, "
                + "`world` varchar(25) default NULL, "
                + "`owner` varchar(16) NOT NULL, "
                + "`type_id` int(11) default NULL, "
                + "`type` varchar(64) default NULL, "
                + "`data` tinyint default 0, "
                + ((isMySql) ?
                        "UNIQUE KEY `uq_pstone_unbreakables_1` (`x`,`y`,`z`,`world`))"
                        : "UNIQUE (`x`,`y`,`z`,`world`))"));
    }
    
    private void createGriefUndo() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS `pstone_grief_undo` ("
                + ((isMySql) ? "`id` bigint(20) NOT NULL auto_increment PRIMARY KEY, " : "`id` INTEGER PRIMARY KEY, ")
                + "`date_griefed` bigint(20), "
                + "`field_x` int(11) default NULL, "
                + "`field_y` int(11) default NULL, "
                + "`field_z` int(11) default NULL, "
                + "`world` varchar(25) NOT NULL, "
                + "`x` int(11) default NULL, "
                + "`y` int(11) default NULL, "
                + "`z` int(11) default NULL, "
                + "`type_id` int(11) NOT NULL, "
                + "`type` varchar(64) default NULL, "
                + "`data` TINYINT NOT NULL, "
                + "`sign_text` varchar(75) NOT NULL)");
    }
    
    private void createTranslocations() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS `pstone_translocations` ("
                + (isMySql ? "`id` bigint(20) NOT NULL auto_increment PRIMARY KEY, " : "`id` INTEGER PRIMARY KEY, ")
                + "`name` varchar(36) NOT NULL, "
                + "`player_name` varchar(16) NOT NULL, "
                + "`minx` int(11) default NULL, "
                + "`maxx` int(11) default NULL, "
                + "`miny` int(11) default NULL, "
                + "`maxy` int(11) default NULL, "
                + "`minz` int(11) default NULL, "
                + "`maxz` int(11) default NULL, "
                + (isMySql ? "UNIQUE KEY `uq_trans_1` (`name`,`player_name`))" : "UNIQUE (`name`,`player_name`))"));
    }
    
    private void createStoredBlocks() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS `pstone_storedblocks` ("
                + (isMySql ? "`id` bigint(20) NOT NULL auto_increment PRIMARY KEY, " : "`id` INTEGER PRIMARY KEY,  ")
                + "`name` varchar(36) NOT NULL, "
                + "`player_name` varchar(16) NOT NULL, "
                + "`x` int(11) default NULL, "
                + "`y` int(11) default NULL, "
                + "`z` int(11) default NULL, "
                + "`world` varchar(25) NOT NULL, "
                + "`type_id` int(11) NOT NULL, "
                + "`type` varchar(64) default NULL, "
                + "`data` TINYINT NOT NULL, "
                + "`sign_text` varchar(75) NOT NULL, "
                + "`applied` bit default 0, "
                + "`contents` TEXT NOT NULL, "
                + (isMySql ? "UNIQUE KEY `uq_trans_2` (`x`,`y`,`z`,`world`))" : "UNIQUE (`x`,`y`,`z`,`world`))"));
    }
    
    private void createPlayers() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS `pstone_players` ("
                + "`id` bigint(20), "
                + "`uuid` varchar(36) default NULL, "
                + "`player_name` varchar(16) NOT NULL, "
                + "`last_seen` bigint(20) default NULL, "
                + "flags TEXT default NULL, "
                + "PRIMARY KEY (`player_name`))");
    }
    
    private static final String SNITCHES = "pstone_snitches";
    private void createSnitches() throws SQLException {
        if (tableExists(SNITCHES)) {
            return;
        }
        execute(
                "CREATE TABLE `" + SNITCHES + "` ("
                + "`id` bigint(20), "
                + "`x` int(11) default NULL, "
                + "`y` int(11) default NULL, "
                + "`z` int(11) default NULL, "
                + "`world` varchar(25) default NULL, "
                + "`name` varchar(16) NOT NULL, "
                + "`reason` varchar(20) default NULL, "
                + "`details` varchar(50) default NULL, "
                + "`count` int(11) default NULL, "
                + "`date` varchar(25) default NULL, "
                + "UNIQUE (`x`, `y`, `z`, `world`, `name`, `reason`, `details`))");

        addIndexesIfNecessary();
    }
    
    private static final String PURCHASE_PAYMENTS = "pstone_purchase_payments";
    private void createPurchasePayments() throws SQLException {
        if (tableExists(PURCHASE_PAYMENTS)) {
            return;
        }
        execute(
                "CREATE TABLE `" + PURCHASE_PAYMENTS + "` ("
                + "`id` bigint(20), "
                + "`buyer` varchar(16) default NULL, "
                + "`owner` varchar(16) NOT NULL, "
                + "`item` varchar(20) default NULL, "
                + "`amount` int(11) default NULL, "
                + "`fieldName` varchar(255) default NULL, "
                + "`coords` varchar(255) default NULL)");

        addIndexesIfNecessary();
    }
    
    private void addIndexesIfNecessary() throws SQLException {
        if (addedIndexes) {
            return;
        }
        addedIndexes = true;
        try {
            addIndexes();
        } catch (SQLException ex) {
            /*
             * We need to drop the pstone_purchase_payments and pstone_snitches
             * tables so that the next startup of PreciousStones will call addIndexes() to retry
             */
            try {
                execute("DROP TABLE IF EXISTS " + SNITCHES);
                execute("DROP TABLE IF EXISTS " + PURCHASE_PAYMENTS);
            } catch (SQLException dropTableException) {
                ex.addSuppressed(dropTableException);
            }
            throw ex;
        }

    }
    
    private void addIndexes() throws SQLException {
        if (isMySql) {
            execute("ALTER TABLE `pstone_grief_undo` ADD UNIQUE KEY `key_grief_locs` (`x`, `y`, `z`, `world`)");

            execute("ALTER TABLE `pstone_fields` ADD INDEX `indx_field_owner` (`owner`)");

            execute("ALTER TABLE `pstone_players` ADD UNIQUE `unq_uuid` (uuid)");

            execute("ALTER TABLE `pstone_players` ADD INDEX `inx_player_name` (player_name)");

            execute("ALTER TABLE `pstone_cuboids` ADD INDEX `indx_cuboids_owner` (`owner`)");

            execute("ALTER TABLE `pstone_cuboids` ADD INDEX `indx_cuboids_parent` (`parent`)");

            execute("ALTER TABLE `pstone_unbreakables` ADD INDEX `indx_unbreakables_owner` (`owner`)");

            execute("ALTER TABLE `pstone_storedblocks` ADD INDEX `indx_storedblocks_1` (`name`, `player_name`, `applied`)");

            execute("ALTER TABLE `pstone_storedblocks` ADD INDEX `indx_storedblocks_2` (`name`, `player_name`, `applied`, `type_id`, `data`)");

            execute("ALTER TABLE `pstone_storedblocks` ADD INDEX `indx_storedblocks_3` (`name`, `player_name`, `applied`, `type`, `data`)");
        } else {
            execute("CREATE INDEX IF NOT EXISTS `indx_field_owner` ON `pstone_fields` (`owner`)");

            execute("CREATE UNIQUE INDEX IF NOT EXISTS `indx_players_uuid` ON `pstone_players` (`uuid`)");

            execute("CREATE UNIQUE INDEX IF NOT EXISTS `indx_player_name` ON `pstone_players` (`player_name`)");

            execute("CREATE INDEX IF NOT EXISTS `indx_cuboids_owner` ON `pstone_cuboids` (`owner`)");

            execute("CREATE INDEX IF NOT EXISTS `indx_cuboids_parent` ON `pstone_cuboids` (`parent`)");

            execute("CREATE INDEX IF NOT EXISTS `indx_unbreakables_owner` ON `pstone_unbreakables` (`owner`)");
        }
        PreciousStones.log("Added new indexes to database");
    }
    
    private String getDataType(String table, String column) throws SQLException {
        if (!core.supportsGetDataType()) {
            return "";
        }
        return core.getDataType(conn, table, column);
    }

    void addData() throws SQLException {
        if (!getDataType("pstone_fields", "data").equals("tinyint")) {
            execute("alter table pstone_fields add column data tinyint default 0");
        }

        if (!getDataType("pstone_cuboids", "data").equals("tinyint")) {
            execute("alter table pstone_cuboids add column data tinyint default 0");
        }

        if (!getDataType("pstone_unbreakables", "data").equals("tinyint")) {
            execute("alter table pstone_unbreakables add column data tinyint default 0");
        }
    }

    void addType() throws SQLException {
        if (!getDataType("pstone_fields", "type").equals("varchar")) {
            execute("alter table pstone_fields add column type varchar(64) default NULL");
        }

        if (!getDataType("pstone_cuboids", "type").equals("varchar")) {
            execute("alter table pstone_cuboids add column type varchar(64) default NULL");
        }

        if (!getDataType("pstone_unbreakables", "type").equals("varchar")) {
            execute("alter table pstone_unbreakables add column type varchar(64) default NULL");
        }

        if (!getDataType("pstone_grief_undo", "type").equals("varchar")) {
            execute("alter table pstone_grief_undo add column type varchar(64) default NULL");
        }

        if (!getDataType("pstone_storedblocks", "type").equals("varchar")) {
            execute("alter table pstone_storedblocks add column type varchar(64) default NULL");
        }
    }

    void addSnitchDate() throws SQLException {
        if (!getDataType("pstone_snitches", "date").equals("varchar")) {
            execute("alter table pstone_snitches add column date varchar(25) default NULL");
        }
    }
    
    void resetLastSeen() throws SQLException {
        if (!getDataType("pstone_grief_undo", "date_griefed").equals("bigint")) {
            execute("alter table pstone_grief_undo modify date_griefed bigint");
            execute("update pstone_grief_undo date_griefed = ?", Helper.getMillis());
        }

        if (!getDataType("pstone_fields", "last_used").equals("bigint")) {
            execute("alter table pstone_fields modify last_used bigint");
            execute("update pstone_fields last_used = ?", Helper.getMillis());
        }

        if (!getDataType("pstone_cuboids", "last_used").equals("bigint")) {
            execute("alter table pstone_cuboids modify last_used bigint");
            execute("update pstone_cuboids last_used = ?", Helper.getMillis());
        }

        if (!getDataType("pstone_players", "last_seen").equals("bigint")) {
            execute("alter table pstone_players modify last_seen bigint");
            execute("update pstone_players last_seen = ?", Helper.getMillis());
        }
    }
    
    @Override
    public void close() throws SQLException {
        conn.close();
    }
    
}
