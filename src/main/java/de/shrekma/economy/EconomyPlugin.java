package de.shrekma.economy;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class EconomyPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final String PREFIX = ChatColor.GOLD + "[Eco] " + ChatColor.RESET;

    private final Map<UUID, Double> balances = new HashMap<>();
    private final Map<UUID, Double> bounties = new HashMap<>();
    private final Set<UUID> vanished = new HashSet<>();
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, String> lastBoardText = new HashMap<>();

    private File dataFile;
    private double startBalance;

    private record Parsed(OfflinePlayer player, double amount) {}

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        startBalance = getConfig().getDouble("starting-balance", 100.0);
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        Bukkit.getPluginManager().registerEvents(this, this);
        for (String name : List.of("money", "pay", "bal", "baltop", "bounty", "bounties",
                "vanish", "invsee", "ecsee", "tp")) {
            Objects.requireNonNull(getCommand(name)).setExecutor(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) updateBoard(p);
        }, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::saveData, 6000L, 6000L);

        for (UUID id : vanished) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) applyVanish(p);
        }
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ------------------------------------------------------------ data

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        readMap(y.getConfigurationSection("balances"), balances);
        readMap(y.getConfigurationSection("bounties"), bounties);
        for (String s : y.getStringList("vanished")) {
            try { vanished.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
    }

    private void readMap(ConfigurationSection sec, Map<UUID, Double> target) {
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try { target.put(UUID.fromString(key), sec.getDouble(key)); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        balances.forEach((k, v) -> y.set("balances." + k, v));
        bounties.forEach((k, v) -> y.set("bounties." + k, v));
        List<String> v = new ArrayList<>();
        for (UUID id : vanished) v.add(id.toString());
        y.set("vanished", v);
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save data.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ helpers

    private double bal(UUID id) { return balances.getOrDefault(id, startBalance); }

    private void setBal(UUID id, double v) {
        balances.put(id, Math.max(0, v));
        saveData();
    }

    private String fmt(double v) { return String.format(Locale.US, "%,.2f", v); }

    private void msg(CommandSender s, String text) { s.sendMessage(PREFIX + text); }

    private OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        @SuppressWarnings("deprecation")
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return (off.hasPlayedBefore() || off.isOnline()) ? off : null;
    }

    private Double parseNumber(String s) {
        try {
            double d = Double.parseDouble(s);
            if (Double.isNaN(d) || Double.isInfinite(d) || d < 0) return null;
            return Math.round(d * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Accepts "<Player> <Number>" or "<Number> <Player>". */
    private Parsed parsePlayerAndAmount(CommandSender s, String a, String b) {
        Double amount = parseNumber(b);
        String name = a;
        if (amount == null) {
            amount = parseNumber(a);
            name = b;
        }
        if (amount == null) { msg(s, ChatColor.RED + "Please enter a valid positive number."); return null; }
        OfflinePlayer p = find(name);
        if (p == null) { msg(s, ChatColor.RED + "Player not found: " + name); return null; }
        return new Parsed(p, amount);
    }

    private String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase()) {
            case "money" -> money(sender, args);
            case "bal" -> showBalance(sender, args);
            case "pay" -> pay(sender, args);
            case "baltop" -> baltop(sender);
            case "bounty" -> bounty(sender, args);
            case "bounties" -> bountyList(sender);
            case "vanish" -> toggleVanish(sender);
            case "invsee" -> see(sender, args, false);
            case "ecsee" -> see(sender, args, true);
            case "tp" -> tp(sender, args);
        }
        return true;
    }

    private void showBalance(CommandSender s, String[] args) {
        OfflinePlayer target;
        if (args.length == 0) {
            if (!(s instanceof Player self)) { msg(s, "Usage: /bal <Player>"); return; }
            target = self;
        } else {
            target = find(args[0]);
            if (target == null) { msg(s, ChatColor.RED + "Player not found."); return; }
        }
        msg(s, ChatColor.YELLOW + Objects.toString(target.getName(), "?") + ChatColor.GRAY + " has "
                + ChatColor.GREEN + "$" + fmt(bal(target.getUniqueId())));
    }

    private void money(CommandSender s, String[] args) {
        if (args.length >= 3 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("set"))) {
            if (!s.hasPermission("economy.admin")) { msg(s, ChatColor.RED + "No permission."); return; }
            Parsed p = parsePlayerAndAmount(s, args[1], args[2]);
            if (p == null) return;
            UUID id = p.player().getUniqueId();
            if (args[0].equalsIgnoreCase("add")) setBal(id, bal(id) + p.amount());
            else setBal(id, p.amount());
            msg(s, ChatColor.GRAY + "New balance of " + p.player().getName() + ": "
                    + ChatColor.GREEN + "$" + fmt(bal(id)));
            return;
        }
        showBalance(s, args);
    }

    private void pay(CommandSender s, String[] args) {
        if (!(s instanceof Player from)) { msg(s, "Players only."); return; }
        if (args.length != 2) { msg(s, "Usage: /pay <Player> <Number>"); return; }
        Parsed p = parsePlayerAndAmount(s, args[0], args[1]);
        if (p == null) return;
        if (p.amount() <= 0) { msg(s, ChatColor.RED + "Amount must be greater than 0."); return; }
        if (p.player().getUniqueId().equals(from.getUniqueId())) {
            msg(s, ChatColor.RED + "You can't pay yourself."); return;
        }
        if (bal(from.getUniqueId()) < p.amount()) { msg(s, ChatColor.RED + "Not enough money."); return; }

        setBal(from.getUniqueId(), bal(from.getUniqueId()) - p.amount());
        setBal(p.player().getUniqueId(), bal(p.player().getUniqueId()) + p.amount());
        msg(s, ChatColor.GRAY + "You paid " + ChatColor.YELLOW + p.player().getName()
                + ChatColor.GREEN + " $" + fmt(p.amount()));
        Player recv = p.player().getPlayer();
        if (recv != null) {
            msg(recv, ChatColor.YELLOW + from.getName() + ChatColor.GRAY + " paid you "
                    + ChatColor.GREEN + "$" + fmt(p.amount()));
        }
    }

    private void baltop(CommandSender s) {
        // Includes vanished players on purpose.
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(balances.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        msg(s, ChatColor.GOLD + "Richest players:");
        int i = 1;
        for (Map.Entry<UUID, Double> e : list) {
            if (i > 10) break;
            s.sendMessage(ChatColor.GRAY + "" + i + ". " + ChatColor.YELLOW + nameOf(e.getKey())
                    + ChatColor.GRAY + " - " + ChatColor.GREEN + "$" + fmt(e.getValue()));
            i++;
        }
    }

    private void bounty(CommandSender s, String[] args) {
        if (args.length != 3) {
            msg(s, "Usage: /bounty add <Player> <Number>");
            return;
        }
        String sub = args[0].toLowerCase();
        if (!sub.equals("add") && !sub.equals("set")) { msg(s, "Usage: /bounty add <Player> <Number>"); return; }
        boolean admin = s.hasPermission("economy.admin");
        if (sub.equals("set") && !admin) { msg(s, ChatColor.RED + "No permission."); return; }

        Parsed p = parsePlayerAndAmount(s, args[1], args[2]);
        if (p == null) return;
        UUID target = p.player().getUniqueId();

        if (sub.equals("set")) {
            if (p.amount() <= 0) bounties.remove(target); else bounties.put(target, p.amount());
            saveData();
            msg(s, ChatColor.GRAY + "Bounty on " + p.player().getName() + " is now "
                    + ChatColor.RED + "$" + fmt(bounties.getOrDefault(target, 0.0)));
            return;
        }

        if (p.amount() <= 0) { msg(s, ChatColor.RED + "Amount must be greater than 0."); return; }
        if (!admin) { // normal players pay from their own balance, operators add for free
            if (!(s instanceof Player from)) return;
            if (from.getUniqueId().equals(target)) { msg(s, ChatColor.RED + "You can't put a bounty on yourself."); return; }
            if (bal(from.getUniqueId()) < p.amount()) { msg(s, ChatColor.RED + "Not enough money."); return; }
            setBal(from.getUniqueId(), bal(from.getUniqueId()) - p.amount());
        }
        bounties.merge(target, p.amount(), Double::sum);
        saveData();
        Bukkit.broadcastMessage(PREFIX + ChatColor.RED + "A bounty of $" + fmt(p.amount()) + " was placed on "
                + p.player().getName() + " (total $" + fmt(bounties.get(target)) + ")");
    }

    private void bountyList(CommandSender s) {
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(bounties.entrySet());
        list.removeIf(e -> e.getValue() <= 0);
        if (list.isEmpty()) { msg(s, ChatColor.GRAY + "There are no bounties right now."); return; }
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        msg(s, ChatColor.RED + "Bounties:");
        for (Map.Entry<UUID, Double> e : list) {
            s.sendMessage(ChatColor.YELLOW + nameOf(e.getKey()) + ChatColor.GRAY + " - "
                    + ChatColor.RED + "$" + fmt(e.getValue()));
        }
    }

    private void see(CommandSender s, String[] args, boolean enderChest) {
        if (!(s instanceof Player viewer)) { msg(s, "Players only."); return; }
        if (!s.hasPermission("economy.admin")) { msg(s, ChatColor.RED + "No permission."); return; }
        if (args.length != 1) { msg(s, "Usage: /" + (enderChest ? "ecsee" : "invsee") + " <Player>"); return; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { msg(s, ChatColor.RED + "Player must be online."); return; }
        viewer.openInventory(enderChest ? target.getEnderChest() : target.getInventory());
    }

    private void tp(CommandSender s, String[] args) {
        if (!s.hasPermission("economy.admin")) { msg(s, ChatColor.RED + "No permission."); return; }
        if (args.length == 1 && s instanceof Player self) {
            Player t = Bukkit.getPlayerExact(args[0]);
            if (t == null) { msg(s, ChatColor.RED + "Player not found."); return; }
            self.teleport(t);
        } else if (args.length == 2 && Bukkit.getPlayerExact(args[0]) != null
                && Bukkit.getPlayerExact(args[1]) != null) {
            Bukkit.getPlayerExact(args[0]).teleport(Bukkit.getPlayerExact(args[1]));
        } else if (args.length > 0) {
            Bukkit.dispatchCommand(s, "minecraft:tp " + String.join(" ", args)); // coordinates etc.
        } else {
            msg(s, "Usage: /tp <Player>");
        }
    }

    // ------------------------------------------------------------ vanish

    private void toggleVanish(CommandSender s) {
        if (!(s instanceof Player p)) { msg(s, "Players only."); return; }
        if (vanished.contains(p.getUniqueId())) {
            vanished.remove(p.getUniqueId());
            for (Player o : Bukkit.getOnlinePlayers()) o.showPlayer(this, p);
            p.setCollidable(true);
            p.setSleepingIgnored(false);
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
            msg(p, ChatColor.GRAY + "Vanish " + ChatColor.RED + "off");
        } else {
            vanished.add(p.getUniqueId());
            applyVanish(p);
            msg(p, ChatColor.GRAY + "Vanish " + ChatColor.GREEN + "on" + ChatColor.GRAY + " - you can fly.");
        }
        saveData();
    }

    private void applyVanish(Player p) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(p) && !o.hasPermission("economy.vanish.see")) o.hidePlayer(this, p);
        }
        p.setAllowFlight(true);
        p.setCollidable(false);
        p.setSleepingIgnored(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        balances.putIfAbsent(p.getUniqueId(), startBalance);
        for (UUID id : vanished) {
            Player v = Bukkit.getPlayer(id);
            if (v != null && !v.equals(p) && !p.hasPermission("economy.vanish.see")) p.hidePlayer(this, v);
        }
        if (vanished.contains(p.getUniqueId())) {
            e.setJoinMessage(null);
            applyVanish(p);
        }
        updateBoard(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (vanished.contains(p.getUniqueId())) e.setQuitMessage(null);
        boards.remove(p.getUniqueId());
        lastBoardText.remove(p.getUniqueId());
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && vanished.contains(p.getUniqueId())) e.setCancelled(true);
    }

    // ------------------------------------------------------------ bounty kills

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        double reward = bounties.getOrDefault(victim.getUniqueId(), 0.0);
        if (reward <= 0) return;

        // Only count if the LAST damage was a direct hit by a player (melee or projectile).
        // Fall damage, fire, drowning, ... after a hit => no bounty.
        EntityDamageEvent last = victim.getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent hit)) return;

        Player killer = null;
        Entity damager = hit.getDamager();
        if (damager instanceof Player p) killer = p;
        else if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p2) killer = p2;

        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return; // self-kill

        bounties.remove(victim.getUniqueId());
        setBal(killer.getUniqueId(), bal(killer.getUniqueId()) + reward);
        Bukkit.broadcastMessage(PREFIX + ChatColor.YELLOW + killer.getName() + ChatColor.GRAY
                + " collected the bounty of " + ChatColor.RED + "$" + fmt(reward)
                + ChatColor.GRAY + " on " + ChatColor.YELLOW + victim.getName());
    }

    // ------------------------------------------------------------ sidebar

    private void updateBoard(Player p) {
        double money = bal(p.getUniqueId());
        double bounty = bounties.getOrDefault(p.getUniqueId(), 0.0);
        String moneyLine = ChatColor.GREEN + "$" + fmt(money);
        String bountyLine = bounty > 0 ? ChatColor.RED + "Bounty: $" + fmt(bounty) : null;
        String key = moneyLine + "|" + bountyLine;
        if (key.equals(lastBoardText.get(p.getUniqueId()))) return;

        Scoreboard sb = boards.computeIfAbsent(p.getUniqueId(),
                k -> Bukkit.getScoreboardManager().getNewScoreboard());
        Objective old = sb.getObjective("eco");
        if (old != null) old.unregister();
        @SuppressWarnings("deprecation")
        Objective o = sb.registerNewObjective("eco", "dummy", ChatColor.GOLD + "" + ChatColor.BOLD + "Money");
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        o.getScore(moneyLine).setScore(2);
        if (bountyLine != null) o.getScore(bountyLine).setScore(1);
        p.setScoreboard(sb);
        lastBoardText.put(p.getUniqueId(), key);
    }
}
