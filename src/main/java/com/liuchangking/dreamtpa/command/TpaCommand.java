package com.liuchangking.dreamtpa.command;

import com.liuchangking.dreamengine.utils.MessageUtil;
import com.liuchangking.dreamtpa.DreamTPA;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class TpaCommand implements CommandExecutor, TabCompleter {

    private final DreamTPA plugin;

    public TpaCommand(DreamTPA plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("dreamtpa.admin")) {
                MessageUtil.sendWrongMsg(sender, "你没有权限使用该命令");
                return true;
            }
            try {
                plugin.reloadMysqlConfig();
                MessageUtil.sendMsg(sender, "DreamTPA 配置已从 MySQL 重新加载");
            } catch (Exception e) {
                MessageUtil.sendWrongMsg(sender, "DreamTPA 配置重载失败: " + e.getMessage());
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            MessageUtil.sendWrongMsg(sender, "只有玩家可以使用该命令");
            return true;
        }
        if (args.length == 0) {
            plugin.getGui().openMain(player);
            return true;
        }
        if (args.length == 1) {
            plugin.sendRequest(player, args[0]);
            return true;
        }
        MessageUtil.notifyError(player, "用法错误", "用法: /" + label + " [玩家]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender instanceof Player player) {
            String prefix = args[0].toLowerCase();
            List<String> result = plugin.getAvailablePlayerNames(player).stream()
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
            if (sender.hasPermission("dreamtpa.admin") && "reload".startsWith(prefix)) {
                result.add("reload");
            }
            return result;
        }
        return Collections.emptyList();
    }
}
