package com.liuchangking.dreamtpa.command;

import com.liuchangking.dreamtpa.DreamTPA;
import com.liuchangking.dreamtpa.request.TeleportRequest;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * 处理同意传送请求的命令
 */
public class TpAcceptCommand implements CommandExecutor, TabCompleter {

    private final DreamTPA plugin;

    public TpAcceptCommand(DreamTPA plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("只有玩家可以使用该命令");
            return true;
        }
        Player target = (Player) sender;
        TeleportRequest request;
        if (args.length >= 1) {
            String requesterName = args[0];
            request = plugin.getRequestByTarget(target.getName(), requesterName);
            if (request == null) {
                plugin.removeRemoteRequest(target.getName(), requesterName);
                plugin.addPendingTeleport(requesterName, target.getLocation());
                plugin.forwardCommand(target, "TpAccept", requesterName);
                return true;
            }
        } else {
            request = plugin.getRequestByTarget(target.getName());
            if (request == null) {
                List<String> requesters = plugin.getRequesters(target.getName());
                if (!requesters.isEmpty()) {
                    String requesterName = requesters.get(requesters.size() - 1);
                    plugin.removeRemoteRequest(target.getName(), requesterName);
                    plugin.addPendingTeleport(requesterName, target.getLocation());
                    plugin.forwardCommand(target, "TpAccept", requesterName);
                } else {
                    plugin.forwardCommand(target, "TpAccept");
                }
                return true;
            }
        }
        plugin.removeRequest(request);
        request.getRequester().teleport(target);
        request.getRequester().sendMessage("正在传送到 " + target.getName());
        target.sendMessage("你接受了 " + request.getRequester().getName() + " 的传送请求");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender instanceof Player) {
            String prefix = args[0].toLowerCase();
            List<String> requesters = plugin.getRequesters(((Player) sender).getName());
            return requesters.stream()
                .filter(name -> name.toLowerCase().startsWith(prefix))
                .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
