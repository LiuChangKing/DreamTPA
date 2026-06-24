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

public class TpAcceptCommand implements CommandExecutor, TabCompleter {

    private final DreamTPA plugin;

    public TpAcceptCommand(DreamTPA plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player target)) {
            MessageUtil.sendWrongMsg(sender, "只有玩家可以使用该命令");
            return true;
        }
        plugin.acceptRequest(target, args.length >= 1 ? args[0] : null);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender instanceof Player player) {
            String prefix = args[0].toLowerCase();
            return plugin.getRequesters(player.getUniqueId()).stream()
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
