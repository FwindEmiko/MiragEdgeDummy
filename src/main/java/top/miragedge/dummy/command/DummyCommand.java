package top.miragedge.dummy.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import top.miragedge.dummy.MiragEdgeDummy;
import top.miragedge.dummy.dummy.DummyManager;
import top.miragedge.dummy.storage.DummyRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /dummy 管理命令。
 *
 * <p>子命令：give / reload / list / remove / info。</p>
 *
 * <p>实现要点（见 docs/DEVELOPMENT.md §8）：</p>
 * <ul>
 *   <li>玩家向必须走物品交互，命令仅管理员使用（permission 见 plugin.yml）</li>
 *   <li>消息全部走 plugin.messages()，中文 + 占位符，前缀自动拼接</li>
 *   <li>onTabComplete 按权限过滤补全项</li>
 * </ul>
 */
public class DummyCommand implements CommandExecutor, TabCompleter {

    private final MiragEdgeDummy plugin;
    private final DummyManager dummyManager;

    public DummyCommand(MiragEdgeDummy plugin, DummyManager dummyManager) {
        this.plugin = plugin;
        this.dummyManager = dummyManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.messages().fmt("messages.invalid-usage"));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "give":
                handleGive(sender, args);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "list":
                handleList(sender);
                break;
            case "remove":
                handleRemove(sender, args);
                break;
            case "info":
                handleInfo(sender, args);
                break;
            default:
                sender.sendMessage(plugin.messages().fmt("messages.invalid-usage"));
                break;
        }
        return true;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("miragedgedummy.give")) {
            sender.sendMessage(plugin.messages().fmt("messages.no-permission"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.messages().fmt("messages.invalid-usage"));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.messages().fmt("messages.player-not-found"));
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.messages().fmt("messages.invalid-amount"));
            return;
        }
        if (amount <= 0) {
            sender.sendMessage(plugin.messages().fmt("messages.invalid-amount"));
            return;
        }
        // 可选 [生命] 参数：假人最大生命值（PVP 大厅练手：不同血量假人）
        int hp = 0;
        if (args.length >= 4) {
            try {
                hp = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.messages().fmt("messages.invalid-amount"));
                return;
            }
            if (hp <= 0) {
                sender.sendMessage(plugin.messages().fmt("messages.invalid-amount"));
                return;
            }
        }
        // 按物品堆叠上限拆分为多组逐步放入背包（ARMOR_STAND 上限 16，非 64）
        ItemStack sample = dummyManager.createDummyItem(1);
        int maxStack = sample.getMaxStackSize();
        int granted = 0;
        int remaining = amount;
        while (remaining > 0) {
            int part = Math.min(remaining, maxStack);
            Map<Integer, ItemStack> leftover = target.getInventory().addItem(dummyManager.createDummyItem(part, hp));
            if (!leftover.isEmpty()) {
                for (ItemStack stack : leftover.values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), stack);
                }
                target.sendMessage(plugin.messages().fmt("messages.inventory-full"));
            }
            granted += part;
            remaining -= part;
        }
        sender.sendMessage(plugin.messages().fmt("messages.gave-item",
                "player", target.getName(), "amount", String.valueOf(granted)));
        target.sendMessage(plugin.messages().fmt("messages.received-item",
                "amount", String.valueOf(granted)));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("miragedgedummy.reload")) {
            sender.sendMessage(plugin.messages().fmt("messages.no-permission"));
            return;
        }
        plugin.getConfigManager().reload();
        sender.sendMessage(plugin.messages().fmt("messages.reload-success"));
    }

    private void handleList(CommandSender sender) {
        if (!sender.hasPermission("miragedgedummy.admin")) {
            sender.sendMessage(plugin.messages().fmt("messages.no-permission"));
            return;
        }
        Collection<DummyRecord> all = plugin.getStorage().all();
        sender.sendMessage(plugin.messages().fmt("messages.list-header", "count", String.valueOf(all.size())));
        for (DummyRecord record : all) {
            String ownerName = Bukkit.getOfflinePlayer(record.owner()).getName();
            if (ownerName == null) {
                ownerName = record.owner().toString();
            }
            sender.sendMessage(plugin.messages().fmt("messages.list-item",
                    "id", record.uuid().toString(),
                    "owner", ownerName,
                    "world", record.world()));
        }
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("miragedgedummy.admin")) {
            sender.sendMessage(plugin.messages().fmt("messages.no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.messages().fmt("messages.invalid-usage"));
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(plugin.messages().fmt("messages.invalid-uuid"));
            return;
        }
        if (plugin.getStorage().get(uuid) == null) {
            sender.sendMessage(plugin.messages().fmt("messages.no-such-npc", "id", args[1]));
            return;
        }
        if (dummyManager.removeDummy(uuid)) {
            sender.sendMessage(plugin.messages().fmt("messages.remove-success", "id", args[1]));
        } else {
            sender.sendMessage(plugin.messages().fmt("messages.remove-failed", "id", args[1]));
        }
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("miragedgedummy.admin")) {
            sender.sendMessage(plugin.messages().fmt("messages.no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.messages().fmt("messages.invalid-usage"));
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(plugin.messages().fmt("messages.invalid-uuid"));
            return;
        }
        DummyRecord record = plugin.getStorage().get(uuid);
        if (record == null) {
            sender.sendMessage(plugin.messages().fmt("messages.no-such-npc", "id", args[1]));
            return;
        }
        String ownerName = Bukkit.getOfflinePlayer(record.owner()).getName();
        if (ownerName == null) {
            ownerName = record.owner().toString();
        }
        sender.sendMessage(plugin.messages().fmt("messages.info-header", "id", record.uuid().toString()));
        sender.sendMessage(plugin.messages().fmt("messages.info-owner", "owner", ownerName));
        if (record.hp() > 0) {
            sender.sendMessage(plugin.messages().fmt("messages.info-hp", "hp", String.valueOf(record.hp())));
        }
        sender.sendMessage(plugin.messages().fmt("messages.info-world", "world", record.world()));
        sender.sendMessage(plugin.messages().fmt("messages.info-location",
                "x", String.format(Locale.ROOT, "%.2f", record.x()),
                "y", String.format(Locale.ROOT, "%.2f", record.y()),
                "z", String.format(Locale.ROOT, "%.2f", record.z())));
        sender.sendMessage(plugin.messages().fmt("messages.info-facing",
                "yaw", String.format(Locale.ROOT, "%.1f", record.yaw()),
                "pitch", String.format(Locale.ROOT, "%.1f", record.pitch())));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> candidates = new ArrayList<>();
            if (sender.hasPermission("miragedgedummy.give")) {
                candidates.add("give");
            }
            if (sender.hasPermission("miragedgedummy.reload")) {
                candidates.add("reload");
            }
            if (sender.hasPermission("miragedgedummy.admin")) {
                candidates.add("list");
                candidates.add("remove");
                candidates.add("info");
            }
            String prefix = args[0].toLowerCase();
            List<String> result = new ArrayList<>();
            for (String c : candidates) {
                if (c.toLowerCase().startsWith(prefix)) {
                    result.add(c);
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase();
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) {
                    names.add(p.getName());
                }
            }
            return names;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return Collections.singletonList("1");
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            return Collections.singletonList("100");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("info"))) {
            String prefix = args[1].toLowerCase();
            List<String> ids = new ArrayList<>();
            for (DummyRecord record : plugin.getStorage().all()) {
                String id = record.uuid().toString();
                if (id.toLowerCase().startsWith(prefix)) {
                    ids.add(id);
                }
            }
            return ids;
        }
        return Collections.emptyList();
    }
}
