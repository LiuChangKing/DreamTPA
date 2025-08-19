package com.liuchangking.dreamtpa.command;

import com.liuchangking.dreamtpa.DreamTPA;
import com.liuchangking.dreamtpa.request.TeleportRequest;
import com.liuchangking.dreamengine.api.DreamServerAPI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 处理同意传送请求的命令
 */
public class TpAcceptCommand implements CommandExecutor {

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
        TeleportRequest request = plugin.getRequestByTarget(target.getName());
        if (request == null) {
            target.sendMessage("没有待处理的传送请求");
            return true;
        }
        plugin.removeRequest(request);
        String serverId = DreamServerAPI.getPlayerServerId(target.getName());
        DreamServerAPI.sendPlayerToServer(request.getRequester(), serverId);
        request.getRequester().sendMessage("正在传送到 " + target.getName());
        target.sendMessage("你接受了 " + request.getRequester().getName() + " 的传送请求");
        return true;
    }
}
