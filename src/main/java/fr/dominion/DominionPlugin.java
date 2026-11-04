package fr.dominion;

import org.bukkit.block.Container;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public final class DominionPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey itemKey, ownerKey, secondsKey;
    private final Map<String, UUID> caveBlocks = new HashMap<>();
    private final Map<UUID, Set<String>> fakeBlocks = new HashMap<>();
    private BukkitTask visualTask, finderTask;

    @Override public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "item_type");
        ownerKey = new NamespacedKey(this, "owner");
        secondsKey = new NamespacedKey(this, "remaining_seconds");
        loadCaveBlocks();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("dominion")).setExecutor(this);

        long refresh = Math.max(10, getConfig().getLong("cave-block.refresh-ticks", 20));
        visualTask = Bukkit.getScheduler().runTaskTimer(this, this::refreshCaveVision, 20L, refresh);
        finderTask = Bukkit.getScheduler().runTaskTimer(this, this::runFinders, 20L, 20L);
        getLogger().info("FactionDominion enabled.");
    }

    @Override public void onDisable() {
        if (visualTask != null) visualTask.cancel();
        if (finderTask != null) finderTask.cancel();
        restoreAllFakeBlocks();
        saveCaveBlocks();
    }

    private ItemStack makeItem(String type, String name, List<String> lore, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + name);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type);
        meta.setCustomModelData(1000 + Math.abs(type.hashCode() % 8000));
        item.setItemMeta(meta);
        return item;
    }

    private String itemType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    private ItemStack caveItem() {
        return makeItem("cave_block", "Cave Block",
                List.of(ChatColor.GRAY + "Bloc de vision des bases",
                        ChatColor.GRAY + "Révèle les constructions dans une zone limitée",
                        ChatColor.DARK_GRAY + "Les conteneurs et fluides restent opaques"),
                Material.LIGHT_BLUE_STAINED_GLASS);
    }

    private ItemStack finderItem(String variant, int radius) {
        ItemStack item = makeItem("finder_" + variant, "Unclaim Finder " + variant,
                List.of(ChatColor.GRAY + "Détection automatique des conteneurs",
                        ChatColor.GRAY + "Portée : " + radius + " blocs",
                        ChatColor.GRAY + "1 conteneur = 1%",
                        ChatColor.GRAY + "Durée : 140 minutes d'utilisation",
                        ChatColor.YELLOW + "À tenir dans la main"),
                Material.SLIME_BALL);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(secondsKey, PersistentDataType.INTEGER, 8400);
        item.setItemMeta(meta);
        return item;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Commande réservée à un joueur.");
            return true;
        }
        if (!player.hasPermission("dominion.admin")) {
            player.sendMessage(ChatColor.RED + "Tu n'as pas la permission.");
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("give")) {
            player.sendMessage(ChatColor.YELLOW + "Usage : /dominion give <caveblock|finder_basic|finder_advanced|finder_elite>");
            return true;
        }
        ItemStack item = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "caveblock" -> caveItem();
            case "finder_basic" -> finderItem("basic", 8);
            case "finder_advanced" -> finderItem("advanced", 16);
            case "finder_elite" -> finderItem("elite", 24);
            default -> null;
        };
        if (item == null) {
            player.sendMessage(ChatColor.RED + "Objet inconnu.");
        } else {
            player.getInventory().addItem(item).values().forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
            player.sendMessage(ChatColor.GREEN + "Objet Dominion reçu.");
        }
        return true;
    }

    @EventHandler public void onPlace(BlockPlaceEvent event) {
        if (!"cave_block".equals(itemType(event.getItemInHand()))) return;
        Block block = event.getBlockPlaced();
        String key = blockKey(block);
        caveBlocks.put(key, event.getPlayer().getUniqueId());
        saveCaveBlocks();
        event.getPlayer().sendMessage(ChatColor.AQUA + "Cave Block activé. Seul son propriétaire voit l'effet pour le moment.");
    }

    @EventHandler public void onBreak(BlockBreakEvent event) {
        String key = blockKey(event.getBlock());
        if (!caveBlocks.containsKey(key)) return;
        caveBlocks.remove(key);
        saveCaveBlocks();
        refreshCaveVision();
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        restoreFakeBlocks(event.getPlayer());
    }

    private void refreshCaveVision() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<String> desired = new HashSet<>();
            for (Map.Entry<String, UUID> entry : caveBlocks.entrySet()) {
                if (getConfig().getBoolean("cave-block.owner-only", true)
                        && !entry.getValue().equals(player.getUniqueId())) continue;
                Block anchor = blockFromKey(entry.getKey());
                if (anchor == null || !anchor.getWorld().equals(player.getWorld())) continue;
                int r = Math.max(2, getConfig().getInt("cave-block.radius", 8));
                if (player.getLocation().distanceSquared(anchor.getLocation()) > (r * 3.0) * (r * 3.0)) continue;

                for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
                    Block target = anchor.getRelative(x, y, z);
                    if (target.getType().isAir() || target.isLiquid() || target.getState() instanceof TileState) continue;
                    if (!target.getType().isOccluding()) continue;
                    String pos = blockKey(target);
                    desired.add(pos);
                    player.sendBlockChange(target.getLocation(), Material.AIR.createBlockData());
                }
            }
            Set<String> old = fakeBlocks.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
            for (String pos : new HashSet<>(old)) {
                if (!desired.contains(pos)) {
                    Block real = blockFromKey(pos);
                    if (real != null && real.getWorld().equals(player.getWorld()))
                        player.sendBlockChange(real.getLocation(), real.getBlockData());
                }
            }
            old.clear();
            old.addAll(desired);
        }
    }

    private void runFinders() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack held = player.getInventory().getItemInMainHand();
            String type = itemType(held);
            if (type == null || !type.startsWith("finder_")) continue;

            ItemMeta meta = held.getItemMeta();
            int remaining = meta.getPersistentDataContainer().getOrDefault(secondsKey, PersistentDataType.INTEGER, 8400);
            if (remaining <= 0) {
                player.sendActionBar(ChatColor.RED + "Unclaim Finder épuisé");
                continue;
            }
            int radius = switch (type) {
                case "finder_advanced" -> 16;
                case "finder_elite" -> 24;
                default -> 8;
            };
            int containers = 0;
            int r = radius;
            Location loc = player.getLocation();
            for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
                Block b = loc.getBlock().getRelative(x, y, z);
               if (b.getState() instanceof Container) {
    containers++;
}
            }
            int percent = Math.min(100, containers);
            remaining--;
            meta.getPersistentDataContainer().set(secondsKey, PersistentDataType.INTEGER, remaining);
            meta.setLore(List.of(ChatColor.GRAY + "Détection automatique des conteneurs",
                    ChatColor.GRAY + "Portée : " + radius + " blocs",
                    ChatColor.GREEN + "Conteneurs détectés : " + containers,
                    ChatColor.YELLOW + "Signal : " + percent + "%",
                    ChatColor.GRAY + "Durée restante : " + (remaining / 60) + " min"));
            held.setItemMeta(meta);
            player.sendActionBar(ChatColor.GREEN + "Unclaim Finder : " + percent + "% " + ChatColor.DARK_GRAY + "(" + containers + " conteneurs)");
        }
    }

    private String blockKey(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private Block blockFromKey(String key) {
        String[] p = key.split(";");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try { return w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])); }
        catch (NumberFormatException ex) { return null; }
    }

    private void saveCaveBlocks() {
        getConfig().set("placed-cave-blocks", null);
        for (Map.Entry<String, UUID> e : caveBlocks.entrySet())
            getConfig().set("placed-cave-blocks." + Base64.getEncoder().encodeToString(e.getKey().getBytes()), e.getValue().toString());
        saveConfig();
    }

    private void loadCaveBlocks() {
        if (!getConfig().isConfigurationSection("placed-cave-blocks")) return;
        for (String key : Objects.requireNonNull(getConfig().getConfigurationSection("placed-cave-blocks")).getKeys(false)) {
            String pos = new String(Base64.getDecoder().decode(key));
            String uuid = getConfig().getString("placed-cave-blocks." + key);
            try { caveBlocks.put(pos, UUID.fromString(uuid)); } catch (Exception ignored) {}
        }
    }

    private void restoreFakeBlocks(Player player) {
        Set<String> old = fakeBlocks.remove(player.getUniqueId());
        if (old == null) return;
        for (String pos : old) {
            Block real = blockFromKey(pos);
            if (real != null && real.getWorld().equals(player.getWorld()))
                player.sendBlockChange(real.getLocation(), real.getBlockData());
        }
    }

    private void restoreAllFakeBlocks() {
        for (Player player : Bukkit.getOnlinePlayers()) restoreFakeBlocks(player);
    }
}
