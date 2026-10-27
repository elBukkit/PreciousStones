package net.sacredlabyrinth.Phaed.PreciousStones.storage;

import net.sacredlabyrinth.Phaed.PreciousStones.DirtyFieldReason;
import net.sacredlabyrinth.Phaed.PreciousStones.PreciousStones;
import net.sacredlabyrinth.Phaed.PreciousStones.blocks.GriefBlock;
import net.sacredlabyrinth.Phaed.PreciousStones.blocks.TranslocationBlock;
import net.sacredlabyrinth.Phaed.PreciousStones.blocks.Unbreakable;
import net.sacredlabyrinth.Phaed.PreciousStones.entries.BlockTypeEntry;
import net.sacredlabyrinth.Phaed.PreciousStones.entries.PlayerEntry;
import net.sacredlabyrinth.Phaed.PreciousStones.entries.PurchaseEntry;
import net.sacredlabyrinth.Phaed.PreciousStones.entries.SnitchEntry;
import net.sacredlabyrinth.Phaed.PreciousStones.field.Field;
import net.sacredlabyrinth.Phaed.PreciousStones.field.FieldFlag;
import net.sacredlabyrinth.Phaed.PreciousStones.field.FieldSettings;
import net.sacredlabyrinth.Phaed.PreciousStones.helpers.ChatHelper;
import net.sacredlabyrinth.Phaed.PreciousStones.helpers.Helper;
import net.sacredlabyrinth.Phaed.PreciousStones.managers.SettingsManager;
import net.sacredlabyrinth.Phaed.PreciousStones.uuid.UUIDMigration;
import net.sacredlabyrinth.Phaed.PreciousStones.vectors.Vec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.time.temporal.ChronoUnit.DAYS;

/**
 * @author phaed
 */
public class StorageManager {
    /**
     *
     */
    private final DBCore core;
    private final PreciousStones plugin;
    private final Map<Vec, Field> pending = new HashMap<>();
    private final Set<Field> pendingGrief = new HashSet<>();
    private final Map<Unbreakable, Boolean> pendingUb = new HashMap<>();
    private final Map<String, Boolean> pendingPlayers = new HashMap<>();
    private final List<SnitchEntry> pendingSnitchEntries = new ArrayList<>();
    private boolean haltUpdates;

    /**
     *
     */
    public StorageManager() {
        plugin = PreciousStones.getInstance();

        try {
            core = initiateDB();
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to initialise database", ex);
        }
        loadWorldData();
        saverScheduler();
        purgePlayers();
    }

    private DBCore initiateDB() throws SQLException {
        DBCore core;

        SettingsManager settings = plugin.getSettingsManager();
        boolean isMySql = settings.isUseMysql();
        if (isMySql) {
            core = new MySQLCore2(
                    settings.getHost(), settings.getPort(), settings.getDatabase(),
                    settings.getUsername(), settings.getPassword());
        } else {
            core = new SQLiteCore2("PreciousStones", plugin.getDataFolder().getPath());
        }
        try (TableCreator creator = new TableCreator(core)) {
            creator.createTables();

            if (settings.getVersion() < 9) {
                creator.addData();
                settings.setVersion(9);
            }

            if (settings.getVersion() < 10) {
                creator.addSnitchDate();
                settings.setVersion(10);
            }

            if (isMySql && settings.getVersion() < 12) {
                creator.resetLastSeen();
                settings.setVersion(12);
            }

            if (settings.getVersion() < 15) {
                creator.addType();
                settings.setVersion(15);
            }
        }
        return core;
    }
    
    private Connection getConnection() throws SQLException {
        return core.getConnection();
    }

    /**
     * Closes DB connection
     */
    public void closeConnection() {
        core.close();
    }

    /**
     * Load all pstones for any world that is loaded
     */
    public void loadWorldData() {
        PreciousStones.debug("finalizing queue");
        plugin.getForceFieldManager().offerAllDirtyFields();
        processQueue();

        PreciousStones.debug("clearing fields from memory");
        plugin.getForceFieldManager().clearChunkLists();
        plugin.getUnbreakableManager().clearChunkLists();

        final Set<String> worldNames = plugin.getServer().getWorlds().stream()
                .map(World::getName).collect(Collectors.toSet());

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {

            PreciousStones.debug("loading fields by world");

            for (String worldName : worldNames) {
                loadWorldFields(worldName);
                loadWorldUnbreakables(worldName);
            }
        });
    }

    /**
     * Loads all fields for a specific world into memory
     *
     * @param worldName the world name to load
     */
    public void loadWorldFields(String worldName) {
        int fieldCount = 0;
        int cuboidCount = 0;

        List<Field> fields = new ArrayList<>();

        try (Connection conn = getConnection()) {
            synchronized (this) {
                fields.addAll(getFields(conn, worldName));
                fieldCount = fields.size();
                Collection<Field> cuboids = getCuboidFields(conn, worldName);
                cuboidCount = cuboids.size();
                fields.addAll(cuboids);
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        for (Field field : fields) {
            // add to collection

            plugin.getForceFieldManager().addToCollection(field);

            // register grief reverts

            if (field.hasFlag(FieldFlag.GRIEF_REVERT) && field.getRevertingModule().getRevertSecs() > 0) {
                plugin.getGriefUndoManager().register(field);
            }

            // set the initial applied status to the field form the database

            if (field.hasFlag(FieldFlag.TRANSLOCATION) && field.isNamed()) {

                try (Connection conn = getConnection()) {
                    boolean applied = isTranslocationApplied(conn, field.getName(), field.getOwner());
                    field.setDisabled(!applied, true);

                    int count = totalTranslocationCount0(conn, field.getName(), field.getOwner());
                    field.getTranslocatingModule().setTranslocationSize(count);

                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }

            // start renter scheduler

            if (field.hasFlag(FieldFlag.RENTABLE) || field.hasFlag(FieldFlag.SHAREABLE)) {
                field.getRentingModule().scheduleNextRentUpdate();
            }
        }

        if (fieldCount > 0) {
            PreciousStones.log("countsFields", worldName, fieldCount);
        }

        if (cuboidCount > 0) {
            PreciousStones.log("countsCuboids", worldName, cuboidCount);
        }
    }

    public int enableAllFlags(String flagStr) {
        int changed = 0;
        List<Field> fields = new ArrayList<>();

        List<World> worlds = plugin.getServer().getWorlds();

        try (Connection conn = getConnection()) {
            for (World world : worlds) {
                synchronized (this) {
                    String worldName = world.getName();
                    fields.addAll(getFields(conn, worldName));
                    fields.addAll(getCuboidFields(conn, worldName));
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }


        plugin.getForceFieldManager().clearChunkLists();

        for (Field field : fields) {
            if (field.hasFlag(flagStr)) {
                changed++;
                field.getFlagsModule().disableFlag(flagStr, false);
                field.getFlagsModule().dirtyFlags("enableAllFlags");
            }

            plugin.getForceFieldManager().addToCollection(field);
        }

        return changed;
    }

    public int disableAllFlags(String flagStr) {
        int changed = 0;
        List<Field> fields = new ArrayList<>();

        List<World> worlds = plugin.getServer().getWorlds();

        try (Connection conn = getConnection()) {
            for (World world : worlds) {
                synchronized (this) {
                    String worldName = world.getName();
                    fields.addAll(getFields(conn, worldName));
                    fields.addAll(getCuboidFields(conn, worldName));
                }

            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        plugin.getForceFieldManager().clearChunkLists();

        for (Field field : fields) {
            if (field.getFlagsModule().hasDisabledFlag(flagStr)) {
                changed++;
                field.getFlagsModule().enableFlag(flagStr);
                field.getFlagsModule().dirtyFlags("disableAllFlags");
            }

            plugin.getForceFieldManager().addToCollection(field);
        }

        return changed;
    }

    /**
     * Loads all unbreakables for a specific world into memory
     *
     * @param worldName the world name
     */
    public void loadWorldUnbreakables(String worldName) {
        List<Unbreakable> unbreakables = getUnbreakables(worldName);

        for (Unbreakable ub : unbreakables) {
            plugin.getUnbreakableManager().addToCollection(ub);
        }

        if (!unbreakables.isEmpty()) {
            PreciousStones.log("countsUnbreakables", worldName, unbreakables.size());
        }
    }

    /**
     * Puts the field up for future storage
     *
     * @param field
     */
    public void offerField(Field field) {
        synchronized (pending) {
            pending.put(field.toVec(), field);
        }
    }

    /**
     * Puts the field up for grief reversion
     *
     * @param field
     */
    public void offerGrief(Field field) {
        synchronized (pendingGrief) {
            pendingGrief.add(field);
        }
    }


    /**
     * Puts the unbreakable up for future storage
     *
     * @param ub
     * @param insert
     */
    public void offerUnbreakable(Unbreakable ub, boolean insert) {
        synchronized (pendingUb) {
            pendingUb.put(ub, insert);
        }
    }

    /**
     * Puts the player up for future storage
     *
     * @param playerName
     */
    public void offerPlayer(String playerName) {
        synchronized (pendingPlayers) {
            pendingPlayers.put(playerName, true);
        }
    }

    /**
     * Puts the player up for future storage
     *
     * @param playerName
     */
    public void offerDeletePlayer(String playerName) {
        synchronized (pendingPlayers) {
            pendingPlayers.put(playerName, false);
        }
    }

    /**
     * Puts the snitch list up for future storage
     *
     * @param se
     */
    public void offerSnitchEntry(SnitchEntry se) {
        synchronized (pendingSnitchEntries) {
            pendingSnitchEntries.add(se);
        }
    }

    /**
     * Retrieves all fields belonging to a world from the database
     *
     * @param worldName
     * @return
     * @throws SQLException 
     */
    public List<Field> getFields(Connection conn, String worldName) throws SQLException {
        List<Field> out = new ArrayList<>();
        Map<Integer, Material> migrate = new HashMap<>();
        boolean foundInWrongTable = false;

        try (PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT pstone_fields.id as id, x, y, z, radius, height, type_id, type, "
                                + "data, velocity, world, owner, name, packed_allowed, last_used, flags "
                                + "FROM pstone_fields WHERE world = ?")) {
            prepStmt.setString(1, worldName);

            try (ResultSet resultSet = prepStmt.executeQuery()) {
                while (resultSet.next()) {
                    try {
                        long id = resultSet.getLong("id");
                        int x = resultSet.getInt("x");
                        int y = resultSet.getInt("y");
                        int z = resultSet.getInt("z");
                        int radius = resultSet.getInt("radius");
                        int height = resultSet.getInt("height");
                        int type_id = resultSet.getInt("type_id");
                        String typeKey = resultSet.getString("type");
                        float velocity = resultSet.getFloat("velocity");
                        String world = resultSet.getString("world");
                        String owner = resultSet.getString("owner");
                        String name = resultSet.getString("name");
                        String flags = resultSet.getString("flags");
                        String packed_allowed = resultSet.getString("packed_allowed");
                        long last_used = resultSet.getLong("last_used");

                        Material material;
                        if (typeKey == null || typeKey.isEmpty()) {
                            material = Helper.getMaterial(type_id);
                            migrate.put(type_id, material);
                        } else {
                            material = Helper.getMaterial(typeKey);
                        }
                        BlockTypeEntry type = new BlockTypeEntry(material);

                        Field field = new Field(x, y, z, radius, height, velocity, world, type, owner, name, last_used);
                        field.setPackedAllowed(packed_allowed);
                        field.setId(id);

                        FieldSettings fs = plugin.getSettingsManager().getFieldSettings(field);

                        if (fs != null) {
                            field.setSettings(fs);
                            field.getFlagsModule().setFlags(flags);

                            if (fs.getAutoDisableTime() > 0) {
                                field.setDisabled(true, true);
                            }

                            out.add(field);

                            // check for fields in the wrong table

                            if (fs.hasDefaultFlag(FieldFlag.CUBOID)) {
                                deleteFieldFromBothTables(conn, field);
                                insertField0(conn, field);
                                foundInWrongTable = true;
                            }
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }

            migrateMaterials(conn, "pstone_fields", migrate);
        }

        if (foundInWrongTable) {
            System.err.println("[PreciousStones] Fields found in wrong table");
        }

        return out;
    }

    /**
     * Retrieves all of the cuboid fields belonging to a world from the database
     *
     * @param worldName
     * @return
     * @throws SQLException 
     */
    public Collection<Field> getCuboidFields(Connection conn, String worldName) throws SQLException {
        HashMap<Long, Field> out = new HashMap<>();
        boolean foundInWrongTable = false;
        Map<Integer, Material> migrate = new HashMap<>();

        try (PreparedStatement prepStmt = conn.prepareStatement(
                "SELECT pstone_cuboids.id as id, x, y, z, minx, miny, minz, maxx, maxy, maxz, "
                + "type_id, type, data, velocity, world, owner, name, packed_allowed, last_used, flags "
                + "FROM  pstone_cuboids WHERE pstone_cuboids.parent = 0 AND world = ?")) {
            prepStmt.setString(1, worldName);

            try (ResultSet resultSet = prepStmt.executeQuery()) {
                while (resultSet.next()) {
                    try {
                        long id = resultSet.getLong("id");
                        int x = resultSet.getInt("x");
                        int y = resultSet.getInt("y");
                        int z = resultSet.getInt("z");
                        int minx = resultSet.getInt("minx");
                        int miny = resultSet.getInt("miny");
                        int minz = resultSet.getInt("minz");
                        int maxx = resultSet.getInt("maxx");
                        int maxy = resultSet.getInt("maxy");
                        int maxz = resultSet.getInt("maxz");
                        int type_id = resultSet.getInt("type_id");
                        String typeKey = resultSet.getString("type");
                        float velocity = resultSet.getFloat("velocity");
                        String world = resultSet.getString("world");
                        String owner = resultSet.getString("owner");
                        String name = resultSet.getString("name");
                        String flags = resultSet.getString("flags");
                        String packed_allowed = resultSet.getString("packed_allowed");
                        long last_used = resultSet.getLong("last_used");

                        Material material;
                        if (typeKey == null || typeKey.isEmpty()) {
                            material = Helper.getMaterial(type_id);
                            migrate.put(type_id, material);
                        } else {
                            material = Helper.getMaterial(typeKey);
                        }
                        BlockTypeEntry type = new BlockTypeEntry(material);

                        Field field = new Field(x, y, z, minx, miny, minz, maxx, maxy, maxz, velocity, world, type, owner, name, last_used);
                        field.setPackedAllowed(packed_allowed);
                        field.setId(id);

                        FieldSettings fs = plugin.getSettingsManager().getFieldSettings(field);

                        if (fs != null) {
                            field.setSettings(fs);
                            field.getFlagsModule().setFlags(flags);

                            if (fs.getAutoDisableTime() > 0) {
                                field.setDisabled(true, true);
                            }

                            out.put(id, field);

                            // check for fields in the wrong table

                            if (!fs.hasDefaultFlag(FieldFlag.CUBOID)) {
                                deleteFieldFromBothTables(conn, field);
                                insertField0(conn, field);
                                foundInWrongTable = true;
                            }
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
        try (PreparedStatement prepStmt = conn.prepareStatement(
            "SELECT pstone_cuboids.id as id, parent, x, y, z, minx, miny, minz, maxx, maxy, maxz, "
            + "type_id, type, data, velocity, world, owner, name, packed_allowed, last_used, flags "
            + "FROM pstone_cuboids WHERE pstone_cuboids.parent > 0 AND world = ?")) {
            prepStmt.setString(1, worldName);

            try (ResultSet resultSet = prepStmt.executeQuery()) {
                while (resultSet.next()) {
                    try {
                        long id = resultSet.getLong("id");
                        long parent = resultSet.getLong("parent");
                        int x = resultSet.getInt("x");
                        int y = resultSet.getInt("y");
                        int z = resultSet.getInt("z");
                        int minx = resultSet.getInt("minx");
                        int miny = resultSet.getInt("miny");
                        int minz = resultSet.getInt("minz");
                        int maxx = resultSet.getInt("maxx");
                        int maxy = resultSet.getInt("maxy");
                        int maxz = resultSet.getInt("maxz");
                        int type_id = resultSet.getInt("type_id");
                        String typeKey = resultSet.getString("type");
                        float velocity = resultSet.getFloat("velocity");
                        String world = resultSet.getString("world");
                        String owner = resultSet.getString("owner");
                        String name = resultSet.getString("name");
                        String flags = resultSet.getString("flags");
                        String packed_allowed = resultSet.getString("packed_allowed");
                        long last_used = resultSet.getLong("last_used");

                        Material material;
                        if (typeKey == null || typeKey.isEmpty()) {
                            material = Helper.getMaterial(type_id);
                            migrate.put(type_id, material);
                        } else {
                            material = Helper.getMaterial(typeKey);
                        }
                        BlockTypeEntry type = new BlockTypeEntry(material);

                        Field field = new Field(x, y, z, minx, miny, minz, maxx, maxy, maxz, velocity, world, type, owner, name, last_used);
                        field.setPackedAllowed(packed_allowed);

                        Field parentField = out.get(parent);

                        if (parentField != null) {
                            field.setParent(parentField);
                            parentField.addChild(field);
                        } else {
                            field.markForDeletion();
                            offerField(field);
                        }

                        field.setId(id);

                        FieldSettings fs = plugin.getSettingsManager().getFieldSettings(field);

                        if (fs != null) {
                            field.setSettings(fs);
                            field.getFlagsModule().setFlags(flags);
                            out.put(id, field);
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }

        migrateMaterials(conn, "pstone_cuboids", migrate);
        if (foundInWrongTable) {
            PreciousStones.log("fieldsInWrongTable");
        }

        return out.values();
    }

    public void migrate(String oldUsername, String newUsername) {
        plugin.getForceFieldManager().migrateUsername(oldUsername, newUsername);
        plugin.getUnbreakableManager().migrateUsername(oldUsername, newUsername);

        try (Connection conn = getConnection();
                PreparedStatement prepStmt1 = conn.prepareStatement(
                        "UPDATE `pstone_storedblocks` SET player_name = ? WHERE player_name = ?");
                PreparedStatement prepStmt2 = conn.prepareStatement(
                        "UPDATE `pstone_translocations` SET player_name = ? WHERE player_name = ?")) {

            prepStmt1.setString(1, newUsername);
            prepStmt1.setString(2, oldUsername);

            prepStmt2.setString(1, newUsername);
            prepStmt2.setString(2, oldUsername);

            prepStmt1.execute();
            prepStmt2.execute();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        PreciousStones.log("[Username Changed] From: " + oldUsername + " To: " + newUsername);

        Player player = plugin.getServer().getPlayerExact(newUsername);

        if (player != null) {
            ChatHelper.send(player, "usernameChanged");
        }
    }

    public void deletePlayerAndData(String playerName) {
        int purged = plugin.getForceFieldManager().deleteBelonging(playerName);

        if (purged > 0) {
            PreciousStones.log("countsPurgedFields", playerName, purged);
        }

        purged = plugin.getUnbreakableManager().deleteBelonging(playerName);

        if (purged > 0) {
            PreciousStones.log("countsPurgedUnbreakables", playerName, purged);
        }

        offerDeletePlayer(playerName);
    }

    private PlayerEntry extractPlayer(ResultSet resultSet) throws SQLException {
        if (!resultSet.next()) {
            return null;
        }
        PlayerEntry data = new PlayerEntry();
        String uuid = resultSet.getString("uuid");

        // I am not sure how, but I managed to get "null" as a string in my player data
        if (uuid != null && uuid.equalsIgnoreCase("null")) {
            uuid = null;
        }

        String name = resultSet.getString("player_name");
        long last_seen = resultSet.getLong("last_seen");
        String flags = resultSet.getString("flags");

        if (last_seen > 0) {
            ZonedDateTime lastUsedDate = Instant.ofEpochMilli(last_seen).atZone(ZoneId.systemDefault());
            ZonedDateTime now = LocalDateTime.now().atZone(ZoneId.systemDefault());
            int lastSeenDays = (int)DAYS.between(lastUsedDate, now);
            PreciousStones.debug("Player last seen: %s [%s]", lastSeenDays, name);
        }

        data.setName(name);
        data.setFlags(flags);

        if (uuid != null) {
            data.setOnlineUUID(UUID.fromString(uuid));
        } else {
            UUID pulledUUID = UUIDMigration.findPlayerUUID(name);
            if (pulledUUID != null) {
                data.setOnlineUUID(pulledUUID);
                PreciousStones.log("[Online UUID Found] Player: " + name + " UUID: " + pulledUUID.toString());
                plugin.getStorageManager().updatePlayerUUID(name, pulledUUID);
            }
        }

        return data;
    }

    /**
     * Retrieves a player from the database
     */
    public PlayerEntry extractPlayer(String playerName) {
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT * FROM pstone_players WHERE player_name = ?")) {

            prepStmt.setString(1, playerName);
            try (ResultSet resultSet = prepStmt.executeQuery()) {
                return extractPlayer(resultSet);
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return null;
    }

    /**
     * Retrieves a player from the database by UUID, may migrate data if needed
     */
    public PlayerEntry extractPlayer(UUID uuid) {
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT * FROM pstone_players WHERE uuid = ?")) {

            prepStmt.setString(1, uuid.toString());
            try (ResultSet resultSet = prepStmt.executeQuery()) {
                return extractPlayer(resultSet);
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public PlayerEntry createPlayer(String playerName, UUID uuid) {
        PlayerEntry data = new PlayerEntry();
        data.setName(playerName);
        data.setOnlineUUID(uuid);
        PreciousStones.log("[New Player]: " + playerName + " UUID: " + uuid);
        return data;
    }

    /**
     * Purge players from the database
     */
    public void purgePlayers() {
        int purgeDays = plugin.getSettingsManager().getPurgeAfterDays();
        long lastSeen = LocalDateTime.now().atZone(ZoneId.systemDefault()).minusDays(purgeDays).toInstant().toEpochMilli();

        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT player_name FROM pstone_players WHERE last_seen < ?")) {
            
            prepStmt.setLong(1, lastSeen);
            try (ResultSet resultSet = prepStmt.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString("player_name");

                    deletePlayerAndData(name);
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Retrieves all unbreakables belonging to a worlds from the database
     *
     * @param worldName
     * @return
     */
    private List<Unbreakable> getUnbreakables(String worldName) {
        List<Unbreakable> out = new ArrayList<>();
        Map<Integer, Material> migrate = new HashMap<>();

        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement("SELECT * FROM  `pstone_unbreakables` WHERE world = ?")) {

            prepStmt.setString(1, worldName);
            synchronized (this) {
                try (ResultSet resultSet = prepStmt.executeQuery()) {
                    while (resultSet.next()) {

                        int x = resultSet.getInt("x");
                        int y = resultSet.getInt("y");
                        int z = resultSet.getInt("z");
                        int type_id = resultSet.getInt("type_id");
                        String typeKey = resultSet.getString("type");
                        String world = resultSet.getString("world");
                        String owner = resultSet.getString("owner");

                        Material material;
                        if (typeKey == null || typeKey.isEmpty()) {
                            material = Helper.getMaterial(type_id);
                            migrate.put(type_id, material);
                        } else {
                            material = Helper.getMaterial(typeKey);
                        }
                        BlockTypeEntry type = new BlockTypeEntry(material);

                        Unbreakable ub = new Unbreakable(x, y, z, world, type, owner);

                        out.add(ub);
                    }
                }
            }
            migrateMaterials(conn, "pstone_unbreakables", migrate);
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return out;
    }

    private void migrateMaterials(Connection conn, String table, Map<Integer, Material> migrate) throws SQLException {
        if (migrate.isEmpty()) {
            return;
        }

        PreciousStones.getLog().info("Migrating " + migrate.size() + " materials in " + table);
        PreparedStatement prepStmt = conn.prepareStatement("UPDATE `" + table + "` SET type = ? WHERE type_id = ?");

        synchronized (this) {
            for (Map.Entry<Integer, Material> entry : migrate.entrySet()) {
                int typeId = entry.getKey();
                Material material = entry.getValue();
                if (material == null) {
                    PreciousStones.getLog().warning("Error migrating material from type id " + typeId);
                    continue;
                }
                prepStmt.setString(1, Helper.getMaterialName(material));
                prepStmt.setInt(2, typeId);
                prepStmt.execute();
            }
        }
    }

    private void updateGrief(Connection conn, Field field) throws SQLException {
        if (field.isDirty(DirtyFieldReason.GRIEF_BLOCKS)) {
            Queue<GriefBlock> grief = field.getRevertingModule().getGrief();

            for (GriefBlock gb : grief) {
                insertBlockGrief(conn, field, gb);
            }
        }
    }

    private void updateField(Connection conn, Field field) throws SQLException {
        DynamicQueryCreator creator = new DynamicQueryCreator();

        if (field.isDirty(DirtyFieldReason.OWNER)) {
            creator.add("owner = ?", field.getOwner());
        }
        if (field.isDirty(DirtyFieldReason.RADIUS)) {
            creator.add("radius = ?", field.getRadius());
        }
        if (field.isDirty(DirtyFieldReason.HEIGHT)) {
            creator.add("height = ?", field.getHeight());
        }
        if (field.isDirty(DirtyFieldReason.VELOCITY)) {
            creator.add("velocity = ?", field.getVelocity());
        }
        if (field.isDirty(DirtyFieldReason.NAME)) {
            creator.add("name = ?", field.getName());
        }
        if (field.isDirty(DirtyFieldReason.ALLOWED)) {
            creator.add("packed_allowed = ?", field.getPackedAllowed());
        }
        if (field.isDirty(DirtyFieldReason.LASTUSED)) {
            creator.add("last_used = ?", Helper.getMillis());
        }
        if (field.isDirty(DirtyFieldReason.FLAGS)) {
            creator.add("flags = ?", field.getFlagsModule().getFlagsAsString());
        }

        // Query creator for cuboids only, else null
        DynamicQueryCreator cuboidCreator;
        if (field.hasFlag(FieldFlag.CUBOID)) {

            cuboidCreator = new DynamicQueryCreator(creator);
            if (field.isDirty(DirtyFieldReason.DIMENSIONS)) {
                cuboidCreator.add("minx = ?, miny = ?, minz = ?, maxx = ?, maxy = ?, maxz = ?",
                    field.getMinx(), field.getMiny(), field.getMinz(), field.getMaxx(), field.getMaxy(), field.getMaxz());
            }
        } else {
            cuboidCreator = null;
        }
        String fieldUpdates = creator.toQueryString();
        if (fieldUpdates.isEmpty()) {
            return;
        }
        try {
            try (PreparedStatement prepStmt = conn.prepareStatement(
                    "UPDATE `pstone_fields` SET " + fieldUpdates + " "
                    + "WHERE x = ? AND y = ? AND z = ? AND world = ?")) {
                int setCount = creator.setParameters(prepStmt, 0);
                SqlUtils.setFieldCoordinates(prepStmt, field, setCount);
                prepStmt.execute();
            }
            if (cuboidCreator != null) {
                String cuboidUpdates = cuboidCreator.toQueryString();
                if (!cuboidUpdates.isEmpty()) {

                    try (PreparedStatement prepStmt = conn.prepareStatement(
                            "UPDATE `pstone_cuboids` SET " + cuboidUpdates + " "
                            + "WHERE x = ? AND y = ? AND z = ? AND world = ?")) {
                        int setCount = cuboidCreator.setParameters(prepStmt, 0);
                        SqlUtils.setFieldCoordinates(prepStmt, field, setCount);
                        prepStmt.execute();
                    }
                }
            }
        } finally {
            field.clearDirty();
        }
    }

    /**
     * Insert a field into the database
     *
     * @param field
     */
    public void insertField(Field field) {
        try (Connection conn = getConnection()) {

            insertField0(conn, field);
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }
    
    private void insertField0(Connection conn, Field field) throws SQLException {
        Vec vec = field.toVec();

        if (pending.containsKey(vec)) {
            processSingleField(conn, pending.get(field.toVec()));
        }

        String query;
        Object[] parameters;
        if (field.hasFlag(FieldFlag.CUBOID)) {
            query = "INSERT INTO `pstone_cuboids` (`parent`, `x`, `y`, `z`, `world`, `minx`, `miny`, `minz`, "
                    + "`maxx`, `maxy`, `maxz`, `velocity`, `type`, `data`, `owner`, `name`, `packed_allowed`, "
                    + "`last_used`, `flags`) " + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            parameters = new Object[] {
                    ((field.getParent() == null) ? 0 : field.getParent().getId()),
                    field.getX(), field.getY(), field.getZ(),
                    field.getWorld(),
                    field.getMinx(), field.getMiny(), field.getMinz(),
                    field.getMaxx(), field.getMaxy(), field.getMaxz(),
                    field.getVelocity(), Helper.getMaterialName(field.getMaterial()), 0, field.getOwner(), field.getName(),
                    field.getPackedAllowed(), Helper.getMillis(), field.getFlagsModule().getFlagsAsString()
            };
        } else {
            query = "INSERT INTO `pstone_fields` (`x`, `y`, `z`, `world`, `radius`, `height`, `velocity`, "
                    + "`type`, `data`, `owner`, `name`, `packed_allowed`, `last_used`, `flags`) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            parameters = new Object[] {
                    field.getX(), field.getY(), field.getZ(), field.getWorld(), field.getRadius(), field.getHeight(), field.getVelocity(),
                    Helper.getMaterialName(field.getMaterial()), 0, field.getOwner(), field.getName(), field.getPackedAllowed(), Helper.getMillis(),
                    field.getFlagsModule().getFlagsAsString()
            };
        }
        try (PreparedStatement prepStmt = conn.prepareStatement(query, Statement.RETURN_GENERATED_KEYS)) {

            SqlUtils.setArguments(prepStmt, parameters);
            synchronized (this) {
                prepStmt.execute();

                try (ResultSet genKeys = prepStmt.getGeneratedKeys()) {
                    if (genKeys.next()) {
                        field.setId(genKeys.getLong(1));
                        return;
                    }
                }
            }
        }
        synchronized (this) {
            field.setId(0);
        }
    }

    /**
     * Delete a field from the database
     *
     * @param field
     * @throws SQLException 
     */
    private void deleteField(Connection conn, Field field) throws SQLException {

        String table = (field.hasFlag(FieldFlag.CUBOID)) ? "pstone_cuboids" : "pstone_fields";
        String query = "DELETE FROM `{table}` WHERE x = ? AND y = ? AND z = ? AND world = ?";
        query = query .replace("{table}", table);

        Object[] parameters = new Object[] {field.getX(), field.getY(), field.getZ(), field.getWorld()};

        try (PreparedStatement prepStmt = conn.prepareStatement(query)) {

            SqlUtils.setArguments(prepStmt, parameters);
            synchronized (this) {
                prepStmt.execute();
            }
        }
    }

    /**
     * Deletes the field/cuboid from both tables
     *
     * @param field
     * @throws SQLException 
     */
    public void deleteFieldFromBothTables(Connection conn, Field field) throws SQLException {
        try (PreparedStatement prepStmt1 = conn.prepareStatement(
                "DELETE FROM `pstone_fields` WHERE x = ? AND y = ? AND z = ? AND world = ?");
                PreparedStatement prepStmt2 = conn.prepareStatement(
                        "DELETE FROM `pstone_cuboids` WHERE x = ? AND y = ? AND z = ? AND world = ?")) {

            prepStmt1.setInt(1, field.getX());
            prepStmt1.setInt(2, field.getY());
            prepStmt1.setInt(3, field.getZ());
            prepStmt1.setString(4, field.getWorld());

            prepStmt2.setInt(1, field.getX());
            prepStmt2.setInt(2, field.getY());
            prepStmt2.setInt(3, field.getZ());
            prepStmt2.setString(4, field.getWorld());

            synchronized (this) {
                prepStmt1.execute();
                prepStmt2.execute();
            }
        }
    }


    /**
     * Delete a field from the database that a player owns
     *
     * @param playerName
     * @throws SQLException 
     */
    private void deleteFields(Connection conn, String playerName) throws SQLException {
        try (PreparedStatement prepStmt = conn.prepareStatement(
                        "DELETE FROM `pstone_fields` WHERE owner = ?")) {

            prepStmt.setString(1, playerName);
            synchronized (this) {
                prepStmt.execute();
            }
        }
    }

    /**
     * Delete a unbreakables from the database that a player owns
     *
     * @param playerName
     * @throws SQLException 
     */
    private void deleteUnbreakables(Connection conn, String playerName) throws SQLException {
        try (PreparedStatement prepStmt = conn.prepareStatement(
                        "DELETE FROM `pstone_unbreakables` WHERE owner = ?")) {

            prepStmt.setString(1, playerName);
            synchronized (this) {
                prepStmt.execute();
            }
        }
    }

    /**
     * Insert an unbreakable into the database
     *
     * @param ub
     * @throws SQLException 
     */
    private void insertUnbreakable(Connection conn, Unbreakable ub) throws SQLException {
        Object[] parameters = new Object[] {ub.getX(), ub.getY(), ub.getZ(), ub.getWorld(),
                ub.getOwner(), Helper.getMaterialName(ub.getMaterial()), 0};

        try (PreparedStatement prepStmt = conn.prepareStatement(
                        "INSERT INTO `pstone_unbreakables` (`x`, `y`, `z`, `world`, `owner`, `type`, `data`) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {

            SqlUtils.setArguments(prepStmt, parameters);
            synchronized (this) {
                prepStmt.execute();
            }
        }
    }

    /**
     * Delete an unbreakable from the database
     *
     * @param ub
     * @throws SQLException 
     */
    private void deleteUnbreakable(Connection conn, Unbreakable ub) throws SQLException {
        Object[] parameters = new Object[] {ub.getX(), ub.getY(), ub.getZ(), ub.getWorld()};

        try (PreparedStatement prepStmt = conn.prepareStatement(
                        "DELETE FROM `pstone_unbreakables` WHERE x = ? AND y = ? AND z = ? AND world = ?")) {

            SqlUtils.setArguments(prepStmt, parameters);
            synchronized (this) {
                prepStmt.execute();
            }
        }
    }

    /**
     * Insert a pending purchase into the database
     *
     * @param purchase
     */
    public void insertPendingPurchasePayment(PurchaseEntry purchase) {
        BlockTypeEntry item = purchase.getItem();
        String itemName = item == null ? null : item.toString();

        Object[] parameters = new Object[] {purchase.getId(), purchase.getBuyer(), purchase.getOwner(), itemName,
                purchase.getAmount(), purchase.getFieldName(), purchase.getCoords()};

        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "INSERT INTO `pstone_purchase_payments` (`id`, `buyer`, `owner`, `item`, `amount`, `fieldName`, `coords`) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {

            SqlUtils.setArguments(prepStmt, parameters);
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Delete an pending purchase from the database
     *
     * @param purchase
     */
    public void deletePendingPurchasePayment(PurchaseEntry purchase) {
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "DELETE FROM `pstone_purchase_payments` WHERE id = ?")) {

            prepStmt.setInt(1, purchase.getId());
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Retrieves all snitches belonging to a worlds from the database
     *
     * @param owner
     * @return
     */
    public List<PurchaseEntry> getPendingPurchases(String owner) {
        List<PurchaseEntry> out = new ArrayList<>();

        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT * FROM  `pstone_purchase_payments` WHERE owner = ?")) {

            prepStmt.setString(1, owner);
            synchronized (this) {
                try (ResultSet resultSet = prepStmt.executeQuery()) {
                    while (resultSet.next()) {
                        try {
                            int id = resultSet.getInt("id");
                            String buyer = resultSet.getString("buyer");
                            String item = resultSet.getString("item");
                            String fieldName = resultSet.getString("fieldName");
                            String coords = resultSet.getString("coords");
                            int amount = resultSet.getInt("amount");

                            out.add(new PurchaseEntry(id, buyer, owner, fieldName, coords, new BlockTypeEntry(item), amount));
                        } catch (Exception ex) {
                            PreciousStones.getLog().info(ex.getMessage());
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        return out;
    }

    /**
     * Insert snitch entry into the database
     *
     * @param snitch
     * @param se
     */
    public void insertSnitchEntry(Field snitch, SnitchEntry se) {
        try (Connection conn = getConnection()) {

            insertSnitchEntry0(conn, snitch, se);
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }
    
    private void insertSnitchEntry0(Connection conn, Field snitch, SnitchEntry se) throws SQLException {
        long date = Helper.getMillis();

        if (core.getVendorType().supportsInsertOnDuplicateKeyUpdate()) {
            try (PreparedStatement prepStmt = conn.prepareStatement(
                    "INSERT INTO `pstone_snitches` (`x`, `y`, `z`, `world`, `name`, `reason`, `details`, `count`, `date`) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE count = count + 1")) {

                SqlUtils.setArguments(prepStmt,
                        snitch.getX(), snitch.getY(), snitch.getZ(), snitch.getWorld(),
                        se.getName(), se.getReason(), se.getDetails(), 1, date);
                synchronized (this) {
                    prepStmt.execute();
                }
            }
        } else {
            try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT OR IGNORE INTO `pstone_snitches` (`x`, `y`, `z`, `world`, `name`, `reason`, `details`, `count`, `date`) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
                    PreparedStatement updateStmt = conn.prepareStatement(
                            "UPDATE `pstone_snitches` SET count = count + 1 "
                            + "WHERE x = ? AND y = ? AND z = ? AND world = ? AND name = ? AND reason = ? AND details = ?")) {

                SqlUtils.setArguments(insertStmt,
                        snitch.getX(), snitch.getY(), snitch.getZ(), snitch.getWorld(),
                        se.getName(), se.getReason(), se.getDetails(), 0, date);
                SqlUtils.setArguments(updateStmt,
                        snitch.getX(), snitch.getY(), snitch.getZ(), snitch.getWorld(),
                        se.getName(), se.getReason(), se.getDetails());
                synchronized (this) {
                    insertStmt.execute();
                    updateStmt.execute();
                }
            }
        }
    }

    /**
     * Delete all snitch entries for a snitch form the database
     *
     * @param snitch
     */
    public void deleteSnitchEntries(Field snitch) {
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "DELETE FROM `pstone_snitches` WHERE x = ? AND y = ? AND z = ? AND world = ?")) {

            prepStmt.setInt(1, snitch.getX());
            prepStmt.setInt(2, snitch.getY());
            prepStmt.setInt(3, snitch.getZ());
            prepStmt.setString(4, snitch.getWorld());
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Retrieves all snitches belonging to a worlds from the database
     *
     * @param snitch
     * @return
     */
    public List<SnitchEntry> getSnitchEntries(Field snitch) {
        List<SnitchEntry> workingSnitchEntries = new ArrayList<>();

        synchronized (pendingSnitchEntries) {
            workingSnitchEntries.addAll(pendingSnitchEntries);
            pendingSnitchEntries.clear();
        }

        List<SnitchEntry> out = new ArrayList<>();

        try (Connection conn = getConnection()) {

            processSnitches(conn, workingSnitchEntries);
            
            try (PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT * FROM  `pstone_snitches` WHERE x = ? AND y = ? AND z = ? AND world = ? ORDER BY `date` DESC")) {

                prepStmt.setInt(1, snitch.getX());
                prepStmt.setInt(2, snitch.getY());
                prepStmt.setInt(3, snitch.getZ());
                prepStmt.setString(4, snitch.getWorld());
                synchronized (this) {
                    try (ResultSet resultSet = prepStmt.executeQuery()) {
                        while (resultSet.next()) {
                            String name = resultSet.getString("name");
                            String reason = resultSet.getString("reason");
                            String details = resultSet.getString("details");
                            int count = resultSet.getInt("count");

                            SnitchEntry ub = new SnitchEntry(null, name, reason, details, count);

                            out.add(ub);
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return out;
    }

    /**
     * Delete a player from the players table
     *
     * @param playerName
     */
    private void deletePlayer(Connection conn, String playerName) throws SQLException {
        try (PreparedStatement prepStmt = conn.prepareStatement(
                        "DELETE FROM `pstone_players` WHERE player_name = ?")) {

            prepStmt.setString(1, playerName);
            synchronized (this) {
                prepStmt.execute();
            }
        }
    }

    /**
     * Update the player's last seen date on the database
     *
     * @param playerName
     */
    public void updatePlayer(String playerName) {
        try (Connection conn = getConnection()) {

            updatePlayer0(conn, playerName);
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }
    
    private void updatePlayer0(Connection conn, String playerName) throws SQLException {
        long time = Helper.getMillis();
        PlayerEntry data = plugin.getPlayerManager().getPlayerEntry(playerName);

        if (core.getVendorType().supportsInsertOnDuplicateKeyUpdate()) {
            try (PreparedStatement prepStmt = conn.prepareStatement(
                    "INSERT INTO `pstone_players` (`player_name`, `uuid`, `last_seen`, `flags`) "
                    + "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE last_seen = ?, flags = ?")) {

                SqlUtils.setArguments(prepStmt, playerName, data.getOnlineUUID().toString(), time, data.getFlags(), time, data.getFlags());
                synchronized (this) {
                    prepStmt.execute();
                }
            }
        } else {
            try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT OR IGNORE INTO `pstone_players` (`player_name`, `uuid`, `last_seen`, `flags`) "
                    + "VALUES (?, ?, ?, ?)");
                    PreparedStatement updateStmt = conn.prepareStatement(
                            "UPDATE pstone_players SET last_seen = ?, flags = ? WHERE player_name = ?")) {

                SqlUtils.setArguments(insertStmt, playerName, data.getOnlineUUID().toString(), time, data.getFlags());
                SqlUtils.setArguments(updateStmt, time, data.getFlags(), playerName);
                synchronized (this) {
                    insertStmt.execute();
                    updateStmt.execute();
                }
            }
        }
    }

    /**
     * Update the player's uuid
     *
     * @param playerName
     */
    public void updatePlayerUUID(String playerName, UUID uuid) {
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "UPDATE `pstone_players` SET `uuid` = ? WHERE `player_name` = ?")) {

            prepStmt.setString(1, uuid.toString());
            prepStmt.setString(2, playerName);
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Record a single block grief
     *
     * @param field
     * @param gb
     * @throws SQLException 
     */
    private void insertBlockGrief(Connection conn, Field field, GriefBlock gb) throws SQLException {
        Object[] parameters = new Object[] {Helper.getMillis(), field.getX(), field.getY(), field.getZ(), field.getWorld(),
                gb.getX(), gb.getY(), gb.getZ(), Helper.getMaterialName(gb.getType()), 0, gb.getSignText()};

        try (PreparedStatement prepStmt = conn.prepareStatement(
                        "INSERT INTO `pstone_grief_undo` (`date_griefed`, `field_x`, `field_y`, `field_z`, `world`, "
                        + "`x`, `y`, `z`, `type`, `data`, `sign_text`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {

            SqlUtils.setArguments(prepStmt, parameters);
            synchronized (this) {
                prepStmt.execute();
            }
        }
    }

    /**
     * Restores a field's griefed blocks
     *
     * @param field
     * @return
     */
    public Queue<GriefBlock> retrieveBlockGrief(Field field) {
        synchronized (this) {
            haltUpdates = true;
        }

        Set<Field> workingGrief = new HashSet<>();
        Map<Integer, Material> migrate = new HashMap<>();

        synchronized (pendingGrief) {
            workingGrief.addAll(pendingGrief);
            pendingGrief.clear();
        }

        Queue<GriefBlock> out = new LinkedList<>();

        try (Connection conn = getConnection()) {

            processGrief(conn, workingGrief);

            try (PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT * FROM  `pstone_grief_undo` WHERE field_x = ? AND field_y = ? AND field_z = ? AND world = ? ORDER BY y ASC")) {
                SqlUtils.setFieldCoordinates(prepStmt, field);
                synchronized (this) {

                    try (ResultSet resultSet = prepStmt.executeQuery()) {
                        while (resultSet.next()) {

                            int x = resultSet.getInt("x");
                            int y = resultSet.getInt("y");
                            int z = resultSet.getInt("z");
                            int type_id = resultSet.getInt("type_id");
                            String typeKey = resultSet.getString("type");
                            String signText = resultSet.getString("sign_text");

                            Material material;
                            if (typeKey == null || typeKey.isEmpty()) {
                                material = Helper.getMaterial(type_id);
                                migrate.put(type_id, material);
                            } else {
                                material = Helper.getMaterial(typeKey);
                            }
                            BlockTypeEntry type = new BlockTypeEntry(material);

                            GriefBlock gb = new GriefBlock(x, y, z, field.getWorld(), type);

                            if (type_id == 0 || type_id == 8 || type_id == 9 || type_id == 10 || type_id == 11) {
                                gb.setEmpty(true);
                            }

                            gb.setSignText(signText);
                            out.add(gb);
                        }
                    }
                    if (!out.isEmpty()) {
                        PreciousStones.debug("Deleting grief from the db");
                        synchronized (pendingGrief) {
                            pendingGrief.remove(field);
                        }
                        deleteBlockGrief0(conn, field);
                    }
                }
            }
            migrateMaterials(conn, "pstone_grief_undo", migrate);
        } catch (SQLException ex) {
            ex.printStackTrace();
        } finally {
            synchronized (this) {
                haltUpdates = false;
            }
        }
        return out;
    }

    /**
     * Deletes all records from a specific field
     *
     * @param field
     */
    public void deleteBlockGrief(Field field) {
        synchronized (pendingGrief) {
            pendingGrief.remove(field);
        }

        try (Connection conn = getConnection()) {

            deleteBlockGrief0(conn, field);
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }
    
    private void deleteBlockGrief0(Connection conn, Field field) throws SQLException {
        try (PreparedStatement prepStmt = conn.prepareStatement(
                "DELETE FROM `pstone_grief_undo` WHERE field_x = ? AND field_y = ? AND field_z = ? AND world = ?")) {

            SqlUtils.setFieldCoordinates(prepStmt, field);
            synchronized (this) {
                prepStmt.execute();
            }
        }
    }

    /**
     * Deletes all records from a specific block
     *
     * @param block
     */
    public void deleteBlockGrief(Block block) {
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "DELETE FROM `pstone_grief_undo` WHERE x = ? AND y = ? AND z = ? AND world = ?")) {

            prepStmt.setInt(1, block.getX());
            prepStmt.setInt(2, block.getY());
            prepStmt.setInt(3, block.getZ());
            prepStmt.setString(4, block.getWorld().getName());
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Checks if the translocation head record exists
     *
     * @param name
     * @param playerName
     * @return
     */
    public boolean existsTranslocatior(String name, String playerName) {
        boolean exists = false;

        try (Connection conn = getConnection()) {

            exists = existsTranslocatior0(conn, name, playerName);
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return exists;
    }
    
    private boolean existsTranslocatior0(Connection conn, String name, String playerName) throws SQLException {
        boolean exists = false;
        try (PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM `pstone_translocations` WHERE `name` = ? AND `player_name` = ?")) {

            prepStmt.setString(1, name);
            prepStmt.setString(2, playerName);
            synchronized (this) {

                try (ResultSet resultSet = prepStmt.executeQuery()) {
                    while (resultSet.next()) {
                        exists = resultSet.getInt(1) > 0;
                    }
                }
            }
        }
        return exists;
    }

    /**
     * Sets the size of the field
     *
     * @param field
     * @param fieldName
     * @return
     */
    public void changeSizeTranslocatiorField(Field field, String fieldName) {
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT * FROM `pstone_translocations` WHERE `name` = ? AND `player_name` = ? LIMIT 1")) {

            prepStmt.setString(1, fieldName);
            prepStmt.setString(2, field.getOwner());
            synchronized (this) {
                try (ResultSet resultSet = prepStmt.executeQuery()) {
                    while (resultSet.next()) {
                        field.setRelativeCuboidDimensions(
                                resultSet.getInt("minx"), resultSet.getInt("miny"), resultSet.getInt("minz"),
                                resultSet.getInt("maxx"), resultSet.getInt("maxy"), resultSet.getInt("maxz"));
                    }
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Add the head record for the translocation
     *
     * @param field
     * @param name
     */
    public void insertTranslocationHead(Field field, String name) {

        Location relativeMin = field.getRelativeMin();
        Location relativeMax = field.getRelativeMax();
        Object[] parameters = new Object[] {name, field.getOwner(),
                relativeMin.getBlockX(), relativeMin.getBlockY(), relativeMin.getBlockZ(),
                relativeMax.getBlockX(), relativeMax.getBlockY(), relativeMax.getBlockZ()};

        try (Connection conn = getConnection()) {

            if (existsTranslocatior0(conn, name, field.getOwner())) {
                return;
            }
            try (PreparedStatement prepStmt = conn.prepareStatement(
                    "INSERT INTO `pstone_translocations` (`name`, `player_name`, `minx`, `miny`, `minz`, `maxx`, `maxy`, `maxz`) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {

                SqlUtils.setArguments(prepStmt, parameters);
                synchronized (this) {
                    prepStmt.execute();
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Record a single translocation block
     *
     * @param field
     * @param tb
     */
    public void insertTranslocationBlock(Field field, TranslocationBlock tb) {
        insertTranslocationBlock(field, tb, true);
    }

    /**
     * Record a single translocation block
     *
     * @param field
     * @param tb
     * @param applied
     */
    public void insertTranslocationBlock(Field field, TranslocationBlock tb, boolean applied) {

        Object[] parameters = new Object[] {
                field.getName(), field.getOwner(), field.getWorld(), tb.getRx(), tb.getRy(), tb.getRz(),
                tb.getType(), 0, tb.getContents(), tb.getSignText(), (applied ? 1 : 0)
        };

        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "INSERT INTO `pstone_storedblocks` (`name`, `player_name`, `world`, `x`, `y`, `z`, "
                        + "`type_id`, `data`, `contents`, `sign_text`, `applied`) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {

            SqlUtils.setArguments(prepStmt, parameters);
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Retrieves the count of applied translocation blocks
     *
     * @param field
     * @return
     */
    public int appliedTranslocationCount(Field field) {
        int count = 0;
        try (Connection conn = getConnection()) {
            count =  appliedTranslocationCount(conn, field.getName(), field.getOwner());
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return count;
    }

    /**
     * Retrieves the count of applied translocation blocks
     *
     * @param name
     * @param playerName
     * @return
     * @throws SQLException 
     */
    private int appliedTranslocationCount(Connection conn, String name, String playerName) throws SQLException {
        int count = 0;
        try (PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM `pstone_storedblocks` WHERE `name` = ? AND `player_name` = ? AND `applied` = 1")) {

            prepStmt.setString(1, name);
            prepStmt.setString(2, playerName);
            synchronized (this) {
                try (ResultSet resultSet = prepStmt.executeQuery()) {
                    while (resultSet.next()) {
                        count = resultSet.getInt(1);
                    }
                }
            }
        }
        return count;
    }

    /**
     * Retrieves the count of applied translocation blocks
     *
     * @param name
     * @param playerName
     * @return
     */
    public int totalTranslocationCount(String name, String playerName) {
        int count = 0;

        try (Connection conn = getConnection()) {

            count = totalTranslocationCount0(conn, name, playerName);
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return count;
    }
    
    private int totalTranslocationCount0(Connection conn, String name, String playerName) throws SQLException {
        int count = 0;

        try (PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM `pstone_storedblocks` WHERE `name` = ? AND `player_name` = ?")) {

            prepStmt.setString(1, name);
            prepStmt.setString(2, playerName);
            synchronized (this) {

                try (ResultSet res = prepStmt.executeQuery()) {
                    while (res.next()) {
                        count = res.getInt(1);
                    }
                }
            }
        }
        return count;
    }

    /**
     * Retrieves the count of unapplied translocation blocks
     *
     * @param field
     * @return
     */
    public int unappliedTranslocationCount(Field field) {
        return unappliedTranslocationCount(field.getName(), field.getOwner());
    }

    /**
     * Retrieves the count of unapplied translocation blocks
     *
     * @param name
     * @param playerName
     * @return
     */
    public int unappliedTranslocationCount(String name, String playerName) {
        int count = 0;
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM `pstone_storedblocks` WHERE `name` = ? AND `player_name` = ? AND `applied` = 0")) {

            prepStmt.setString(1, name);
            prepStmt.setString(2, playerName);
            synchronized (this) {

                try (ResultSet resultSet = prepStmt.executeQuery()) {
                    while (resultSet.next()) {
                        count = resultSet.getInt(1);
                    }
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return count;
    }

    /**
     * Returns the translocation blocks, and marks them as not-applied on the database
     *
     * @param field
     * @return
     */
    public Queue<TranslocationBlock> retrieveClearTranslocation(Field field) {
        Queue<TranslocationBlock> out = new LinkedList<>();
        Map<Integer, Material> migrate = new HashMap<>();

        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT * FROM  `pstone_storedblocks` WHERE `name` = ? AND `player_name` = ? AND `applied` = 1 ORDER BY y ASC")) {

            SqlUtils.setFieldNameAndOwner(prepStmt, field);
            synchronized (this) {

                try (ResultSet resultSet = prepStmt.executeQuery()) {
                    while (resultSet.next()) {
                        int x = resultSet.getInt("x");
                        int y = resultSet.getInt("y");
                        int z = resultSet.getInt("z");

                        World world = plugin.getServer().getWorld(field.getWorld());

                        Location location = new Location(world, x, y, z);
                        location = location.add(field.getLocation());

                        int type_id = resultSet.getInt("type_id");
                        String typeKey = resultSet.getString("type");
                        String signText = resultSet.getString("sign_text");
                        String contents = resultSet.getString("contents");

                        Material material;
                        if (typeKey == null || typeKey.isEmpty()) {
                            material = Helper.getMaterial(type_id);
                            migrate.put(type_id, material);
                        } else {
                            material = Helper.getMaterial(typeKey);
                        }
                        BlockTypeEntry type = new BlockTypeEntry(material);

                        TranslocationBlock tb = new TranslocationBlock(location, type);

                        if (type_id == 0 || type_id == 8 || type_id == 9 || type_id == 10 || type_id == 11) {
                            tb.setEmpty(true);
                        }

                        tb.setContents(contents);
                        tb.setRelativeCoords(x, y, z);
                        tb.setSignText(signText);
                        out.add(tb);
                    }
                }
            }
            clearTranslocation(conn, field);
            migrateMaterials(conn, "pstone_storedblocks", migrate);

        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        
        return out;
    }

    /**
     * Returns the translocation blocks, and marks them as not-applied on the database
     *
     * @param field
     * @return
     */
    public Queue<TranslocationBlock> retrieveTranslocation(Field field) {
        Queue<TranslocationBlock> out = new LinkedList<>();
        Map<Integer, Material> migrate = new HashMap<>();

        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT * FROM  `pstone_storedblocks` WHERE `name` = ? AND `player_name` = ? AND `applied` = 0 ORDER BY y ASC")) {

            SqlUtils.setFieldNameAndOwner(prepStmt, field);
            synchronized (this) {

                try (ResultSet resultSet = prepStmt.executeQuery()) {
                    while (resultSet.next()) {
                        int x = resultSet.getInt("x");
                        int y = resultSet.getInt("y");
                        int z = resultSet.getInt("z");

                        World world = plugin.getServer().getWorld(field.getWorld());

                        Location location = new Location(world, x, y, z);
                        location = location.add(field.getLocation());

                        int type_id = resultSet.getInt("type_id");
                        String typeKey = resultSet.getString("type");
                        String signText = resultSet.getString("sign_text");
                        String contents = resultSet.getString("contents");

                        Material material;
                        if (typeKey == null || typeKey.isEmpty()) {
                            material = Helper.getMaterial(type_id);
                            migrate.put(type_id, material);
                        } else {
                            material = Helper.getMaterial(typeKey);
                        }
                        BlockTypeEntry type = new BlockTypeEntry(material);

                        TranslocationBlock tb = new TranslocationBlock(location, type);

                        if (type_id == 0 || type_id == 8 || type_id == 9 || type_id == 10 || type_id == 11) {
                            tb.setEmpty(true);
                        }

                        tb.setContents(contents);
                        tb.setRelativeCoords(x, y, z);
                        tb.setSignText(signText);
                        out.add(tb);
                    }
                }
            }
            applyTranslocation(conn, field);
            migrateMaterials(conn, "pstone_storedblocks", migrate);
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return out;
    }

    /**
     * Returns the players stored translocations and their sizes
     *
     * @param playerName
     * @return
     */
    public Map<String, Integer> getTranslocationDetails(String playerName) {
        Map<String, Integer> out = new HashMap<>();

        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT name, COUNT(name) FROM  `pstone_storedblocks` WHERE `player_name` = ? GROUP BY `name`")) {

            prepStmt.setString(1, playerName);
            synchronized (this) {

                try (ResultSet resultSet = prepStmt.executeQuery()) {
                    while (resultSet.next()) {
                        String name = resultSet.getString(1);
                        int count = resultSet.getInt(2);

                        out.put(name, count);
                    }
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return out;
    }

    /**
     * Returns whether a field with that name by that player exists
     *
     * @param name
     * @param playerName
     * @return
     */
    public boolean existsFieldWithName(String name, String playerName) {
        boolean exists = false;
        try (Connection conn = getConnection()) {

            for (String tableSuffix : new String[] {"fields", "cuboids"}) {

                try (PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM  `pstone_" + tableSuffix + "` WHERE `owner` = ? AND `name` = ?")) {

                    prepStmt.setString(1, playerName);
                    prepStmt.setString(2, name);
                    synchronized (this) {

                        try (ResultSet resultSet = prepStmt.executeQuery()) {
                            while (resultSet.next()) {
                                int count = resultSet.getInt(1);
                                exists = count > 0;
                            }
                        }
                    }
                }
                if (exists) {
                    break;
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return exists;
    }

    /**
     * Returns whether there is data witht tha name for that player
     *
     * @param name
     * @param playerName
     * @return
     */
    public boolean existsTranslocationDataWithName(String name, String playerName) {
        boolean exists = false;
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM  `pstone_storedblocks` WHERE `player_name` = ? AND `name` = ?")) {

            prepStmt.setString(1, playerName);
            prepStmt.setString(2, name);
            synchronized (this) {

                try (ResultSet res = prepStmt.executeQuery()) {
                    while (res.next()) {
                        int count = res.getInt(1);
                        exists = count > 0;
                    }
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return exists;
    }

    /**
     * Marks all translocation blocks as applied for a given field
     *
     * @param field
     * @throws SQLException 
     */
    private void applyTranslocation(Connection conn, Field field) throws SQLException {
        try (PreparedStatement prepStmt = conn.prepareStatement(
                "UPDATE `pstone_storedblocks` SET `applied` = 1 WHERE `name` = ? AND `player_name` = ? AND `applied` = 0")) {

            SqlUtils.setFieldNameAndOwner(prepStmt, field);
            synchronized (this) {
                prepStmt.execute();
            }
        }
    }

    /**
     * Marks all translocation blocks as not-applied for a given field
     *
     * @param field
     * @throws SQLException 
     */
    private void clearTranslocation(Connection conn, Field field) throws SQLException {
        try (PreparedStatement prepStmt = conn.prepareStatement(
                "UPDATE `pstone_storedblocks` SET `applied` = 0 WHERE `name` = ? AND `player_name` = ? AND `applied` = 1")) {

            SqlUtils.setFieldNameAndOwner(prepStmt, field);
            synchronized (this) {
                prepStmt.execute();
            }
        }
    }

    /**
     * Deletes all records from a specific field
     *
     * @param field
     */
    public void deleteAppliedTranslocation(Field field) {
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "DELETE FROM `pstone_storedblocks` WHERE `name` = ? AND `player_name` = ? AND `applied` = 1")) {

            SqlUtils.setFieldNameAndOwner(prepStmt, field);
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Deletes a specific block from a translocation field
     *
     * @param field
     * @param tb
     */
    public void deleteTranslocation(Field field, TranslocationBlock tb) {
        Location location = tb.getRelativeLocation();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "DELETE FROM `pstone_storedblocks` WHERE x = ? AND y = ? AND z = ? AND `name` = ? AND `player_name` = ?")) {

            prepStmt.setInt(1, x);
            prepStmt.setInt(2, y);
            prepStmt.setInt(3, z);
            SqlUtils.setFieldNameAndOwner(prepStmt, field, 3);
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Deletes all records from a player
     *
     * @param playerName
     * @throws SQLException 
     */
    private void deleteTranslocation(Connection conn, String playerName) throws SQLException {
        try (PreparedStatement prepStmt = conn.prepareStatement(
                "DELETE FROM `pstone_storedblocks` WHERE `player_name` = ?")) {

            prepStmt.setString(1, playerName);
            synchronized (this) {
                prepStmt.execute();
            }
        }
    }

    /**
     * Deletes all of a translocation's blocks
     *
     * @param name
     * @param playerName
     */
    public void deleteTranslocation(String name, String playerName) {
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "DELETE FROM `pstone_storedblocks` WHERE `player_name` = ? AND `name` = ?")) {

            prepStmt.setString(1, playerName);
            prepStmt.setString(2, name);
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Deletes the translocation's head record
     *
     * @param name
     * @param playerName
     */
    public void deleteTranslocationHead(String name, String playerName) {
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "DELETE FROM `pstone_translocations` WHERE `player_name` = ? AND `name` = ?")) {

            prepStmt.setString(1, playerName);
            prepStmt.setString(2, name);
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Deletes the translocation's head record
     *
     * @param name
     * @param playerName
     * @param block
     * @return
     */
    public int deleteBlockTypeFromTranslocation(String name, String playerName, BlockTypeEntry block) {
        int updateCount = 0;
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "DELETE FROM `pstone_storedblocks` WHERE `player_name` = ? AND `name` = ? AND `type` = ?")) {

            prepStmt.setString(1, playerName);
            prepStmt.setString(2, name);
            prepStmt.setString(3, Helper.getMaterialName(block.getMaterial()));
            synchronized (this) {
                prepStmt.execute();
                updateCount = prepStmt.getUpdateCount();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
        return updateCount;
    }

    /**
     * Changes the owner of a translocation block
     *
     * @param field
     * @param newOwner
     */
    public void changeTranslocationOwner(Field field, String newOwner) {
        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "UPDATE `pstone_storedblocks` SET `player_name` = ? WHERE `name` = ? AND `player_name` = ?")) {

            prepStmt.setString(1, newOwner);
            SqlUtils.setFieldNameAndOwner(prepStmt, field, 1);
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Mark a single translocation block as applied in a field
     *
     * @param field
     * @param tb
     * @param applied
     */
    public void updateTranslocationBlockApplied(Field field, TranslocationBlock tb, boolean applied) {
        Location location = tb.getRelativeLocation();

        Object[] parameters = new Object[] {
                (applied ? 1 : 0), field.getName(), field.getOwner(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()
        };

        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "UPDATE `pstone_storedblocks` SET `applied` = ? WHERE `name` = ? AND `x` = ? AND `y` = ? AND `z` = ?")) {

            SqlUtils.setArguments(prepStmt, parameters);
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Returns whether the translocation is applied or not
     *
     * @param name
     * @param playerName
     * @return
     * @throws SQLException 
     */
    private boolean isTranslocationApplied(Connection conn, String name, String playerName) throws SQLException {
        return appliedTranslocationCount(conn, name, playerName) > 0;
    }

    /**
     * Update a block's content on the database
     *
     * @param field
     * @param tb
     */
    public void updateTranslocationBlockContents(Field field, TranslocationBlock tb) {
        Location location = tb.getRelativeLocation();

        Object[] parameters = new Object[] {
                tb.getContents(), field.getName(), field.getOwner(),
                location.getBlockX(), location.getBlockX(), location.getBlockZ()
        };

        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "UPDATE `pstone_storedblocks` SET `contents` = ? WHERE `name` = ? AND `player_name` = ? AND `x` = ? AND `y` = ? AND `z` = ?")) {

            SqlUtils.setArguments(prepStmt, parameters);
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Update a block's signtext on the database
     *
     * @param field
     * @param tb
     */
    public void updateTranslocationSignText(Field field, TranslocationBlock tb) {
        Location location = tb.getRelativeLocation();

        Object[] parameters = new Object[] {
                tb.getSignText(), field.getName(), field.getOwner(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()
        };

        try (Connection conn = getConnection();
                PreparedStatement prepStmt = conn.prepareStatement(
                        "UPDATE `pstone_storedblocks` SET `sign_text` = ? WHERE `name` = ? "
                        + "AND `player_name` = ? AND `x` = ? AND `y` = ? AND `z` = ?")) {

            SqlUtils.setArguments(prepStmt, parameters);
            synchronized (this) {
                prepStmt.execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Schedules the pending queue on save frequency
     *
     * @return
     */
    public BukkitTask saverScheduler() {
        return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::processQueue,
                0, 20L * plugin.getSettingsManager().getSaveFrequency());
    }

    /**
     * Process entire queue
     */
    public void processQueue() {
        synchronized (this) {
            if (haltUpdates) {
                return;
            }
        }

        Map<Vec, Field> working = new HashMap<>();
        Map<Unbreakable, Boolean> workingUb = new HashMap<>();
        Map<String, Boolean> workingPlayers = new HashMap<>();
        Set<Field> workingGrief = new HashSet<>();
        List<SnitchEntry> workingSnitchEntries = new ArrayList<>();

        synchronized (pending) {
            working.putAll(pending);
            pending.clear();
        }
        synchronized (pendingUb) {
            workingUb.putAll(pendingUb);
            pendingUb.clear();
        }
        synchronized (pendingGrief) {
            workingGrief.addAll(pendingGrief);
            pendingGrief.clear();
        }
        synchronized (pendingPlayers) {
            workingPlayers.putAll(pendingPlayers);
            pendingPlayers.clear();
        }
        synchronized (pendingSnitchEntries) {
            workingSnitchEntries.addAll(pendingSnitchEntries);
            pendingSnitchEntries.clear();
        }

        if (working.isEmpty() && workingUb.isEmpty() && workingGrief.isEmpty()
                && workingPlayers.isEmpty() && workingSnitchEntries.isEmpty()) {
            return;
        }
        try (Connection conn = getConnection()) {

            if (!working.isEmpty()) {
                processFields(conn, working);
            }
            if (!workingUb.isEmpty()) {
                processUnbreakable(conn, workingUb);
            }
            if (!workingGrief.isEmpty()) {
                processGrief(conn, workingGrief);
            }
            if (!workingPlayers.isEmpty()) {
                processPlayers(conn, workingPlayers);
            }
            if (!workingSnitchEntries.isEmpty()) {
                processSnitches(conn, workingSnitchEntries);
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Process suingle field
     *
     * @param field
     */
    private void processSingleField(Connection conn, Field field) throws SQLException {
        if (plugin.getSettingsManager().isDebug()) {
            PreciousStones.getLog().info("[Queue] processing single query");
        }

        if (field.isDirty(DirtyFieldReason.DELETE)) {
            deleteField(conn, field);
        } else {
            updateField(conn, field);
        }

        synchronized (this) {
            pending.remove(field.toVec());
        }
    }

    /**
     * Process pending pstones
     *
     * @param working
     */
    private void processFields(Connection conn, Map<Vec, Field> working) throws SQLException {
        if (plugin.getSettingsManager().isDebug() && !working.isEmpty()) {
            PreciousStones.getLog().info("[Queue] processing " + working.size() + " pstone queries...");
        }

        for (Field field : working.values()) {
            if (field.isDirty(DirtyFieldReason.DELETE)) {
                deleteField(conn, field);
            } else {
                updateField(conn, field);
            }
        }
    }

    /**
     * Process pending grief
     *
     * @param workingUb
     */
    private void processUnbreakable(Connection conn, Map<Unbreakable, Boolean> workingUb) throws SQLException {
        if (plugin.getSettingsManager().isDebug() && !workingUb.isEmpty()) {
            PreciousStones.getLog().info("[Queue] processing " + workingUb.size() + " unbreakable queries...");
        }

        for (Entry<Unbreakable, Boolean> ub : workingUb.entrySet()) {
            if (ub.getValue()) {
                insertUnbreakable(conn, ub.getKey());
            } else {
                deleteUnbreakable(conn, ub.getKey());
            }
        }
    }

    /**
     * Process pending players
     *
     * @param workingPlayers
     * @throws SQLException 
     */
    private void processPlayers(Connection conn, Map<String, Boolean> workingPlayers) throws SQLException {
        if (plugin.getSettingsManager().isDebug() && !workingPlayers.isEmpty()) {
            PreciousStones.getLog().info("[Queue] processing " + workingPlayers.size() + " player queries...");
        }

        for (Map.Entry<String, Boolean> workingPlayerEntry : workingPlayers.entrySet()) {
            String playerName = workingPlayerEntry.getKey();
            if (workingPlayerEntry.getValue()) {
                updatePlayer0(conn, playerName);
            } else {
                deletePlayer(conn, playerName);
                deleteTranslocation(conn, playerName);
                deleteFields(conn, playerName);
                deleteUnbreakables(conn, playerName);
            }
        }

    }

    /**
     * Process pending snitches
     *
     * @param workingSnitchEntries
     * @throws SQLException 
     */
    private void processSnitches(Connection conn, List<SnitchEntry> workingSnitchEntries) throws SQLException {
        if (plugin.getSettingsManager().isDebug() && !workingSnitchEntries.isEmpty()) {
            PreciousStones.getLog().info("[Queue] sending " + workingSnitchEntries.size() + " snitch queries...");
        }

        for (SnitchEntry se : workingSnitchEntries) {
            insertSnitchEntry0(conn, se.getField(), se);
        }
    }

    /**
     * Process pending grief
     *
     * @param workingGrief
     * @throws SQLException 
     */
    private void processGrief(Connection conn, Set<Field> workingGrief) throws SQLException {
        if (plugin.getSettingsManager().isDebug() && !workingGrief.isEmpty()) {
            PreciousStones.getLog().info("[Queue] processing " + workingGrief.size() + " grief queries...");
        }

        for (Field field : workingGrief) {
            updateGrief(conn, field);
        }
    }
}
