package com.liuchangking.dreamtpa.command;

import com.liuchangking.dreamtpa.DreamTPA;
import com.liuchangking.dreamtpa.request.TeleportRequest;
import com.liuchangking.dreamengine.api.DreamServerAPI;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * 处理 /tpa 和 /dreamtpa 命令
 */
public class TpaCommand implements CommandExecutor, TabCompleter {

    private final DreamTPA plugin;

    public TpaCommand(DreamTPA plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("只有玩家可以使用该命令");
            return true;
        }
        Player player = (Player) sender;
        if (args.length != 1) {
            player.sendMessage("用法: /tpa <玩家>");
            return true;
        }
        String targetName = args[0];
        if (player.getName().equalsIgnoreCase(targetName)) {
            player.sendMessage("你不能向自己发送请求");
            return true;
        }
        if (!DreamServerAPI.isPlayerOnline(targetName)) {
            player.sendMessage("该玩家不在线");
            return true;
        }
        TeleportRequest previous = plugin.getRequestByRequester(player.getUniqueId());
        if (previous != null) {
            plugin.removeRequest(previous);
            player.sendMessage("已自动取消之前对 " + previous.getTargetName() + " 的传送请求");
            Player oldTarget = Bukkit.getPlayerExact(previous.getTargetName());
            if (oldTarget != null) {
                oldTarget.sendMessage(player.getName() + " 取消了传送请求");
            } else {
                plugin.sendMessageCrossServer(player, previous.getTargetName(),
                    player.getName() + " 取消了传送请求");
            }
        }
        TeleportRequest request = new TeleportRequest(player, targetName);
        plugin.addRequest(request);
        player.sendMessage("已向 " + targetName + " 发送传送请求, 请在" + plugin.getExpireSeconds() + "秒内保持不动");
        final Player targetPlayer = Bukkit.getPlayerExact(targetName);
        if (targetPlayer != null) {
            targetPlayer.sendMessage(player.getName() + " 请求传送到你这里, 输入 /tpaccept 同意或 /tpdeny 拒绝");
        } else {
            plugin.sendMessageCrossServer(player, targetName,
                player.getName() + " 请求传送到你这里, 输入 /tpaccept 同意或 /tpdeny 拒绝");
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (plugin.getRequestByTarget(targetName) == request) {
                plugin.removeRequest(request);
                player.sendMessage("传送请求已过期");
                if (targetPlayer != null && targetPlayer.isOnline()) {
                    targetPlayer.sendMessage("来自 " + player.getName() + " 的传送请求已过期");
                } else {
                    plugin.sendMessageCrossServer(player, targetName,
                        "来自 " + player.getName() + " 的传送请求已过期");
                }
            }
        }, plugin.getExpireSeconds() * 20L);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return DreamServerAPI.getClusterPlayers().stream()
                .filter(name -> name.toLowerCase().startsWith(prefix))
                .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
