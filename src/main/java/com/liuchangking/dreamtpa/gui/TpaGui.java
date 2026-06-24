package com.liuchangking.dreamtpa.gui;

import com.liuchangking.dreamengine.api.CrossPlatformMenu;
import com.liuchangking.dreamengine.api.CrossUI;
import com.liuchangking.dreamengine.api.PlatformAPI;
import com.liuchangking.dreamengine.ui.ElementsForm;
import com.liuchangking.dreamengine.utils.MessageUtil;
import com.liuchangking.dreamengine.utils.SignGUIUtil;
import com.liuchangking.dreamtpa.DreamTPA;
import com.liuchangking.dreamtpa.request.RemoteTeleportRequest;
import com.liuchangking.dreamtpa.request.TeleportRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;

public class TpaGui implements Listener {

    private static final String MAIN_TITLE = "传送请求";
    private static final String SEND_TITLE = "传送请求 - 申请传送";
    private static final String INBOX_TITLE = "传送请求 - 收到的请求";
    private static final String REMOTE_PREFIX = "remote:";
    private static final String LOCAL_PREFIX = "local:";

    private final DreamTPA plugin;
    private final Set<UUID> viewingInbox = new HashSet<>();
    private final Set<UUID> openingInbox = new HashSet<>();

    public TpaGui(DreamTPA plugin) {
        this.plugin = plugin;
    }

    public void openMain(Player player) {
        viewingInbox.remove(player.getUniqueId());
        CrossPlatformMenu<String> menu = CrossUI.stringMenu(player)
                .title(MAIN_TITLE)
                .content(List.of("选择一个操作"));
        menu.buttonAt(3, Material.ENDER_PEARL, "申请传送", "send");
        menu.buttonAt(6, Material.WRITABLE_BOOK, "收到的请求", "inbox");
        menu.onClick(event -> {
            if ("send".equals(event.getPayload())) {
                openSend(player);
            } else if ("inbox".equals(event.getPayload())) {
                openInbox(player);
            }
        });
        menu.open(player);
    }

    public void openSend(Player player) {
        viewingInbox.remove(player.getUniqueId());
        CrossPlatformMenu<String> menu = CrossUI.stringMenu(player)
                .title(SEND_TITLE)
                .content(List.of("选择要申请传送到的玩家"));
        menu.buttonAt(4, Material.NAME_TAG, "手动输入玩家名", "manual");
        List<String> players = plugin.getAvailablePlayerNames(player);
        if (players.isEmpty()) {
            menu.buttonAt(13, Material.BARRIER, "当前没有可申请的玩家", "empty");
        } else {
            int slot = 9;
            for (String name : players) {
                menu.buttonAt(slot++, Material.ENDER_PEARL, name, "send:" + name);
            }
        }
        menu.onClick(event -> {
            String payload = event.getPayload();
            if ("manual".equals(payload)) {
                openManualInput(player);
            } else if (payload != null && payload.startsWith("send:")) {
                if (plugin.sendRequest(player, payload.substring("send:".length()))) {
                    closeCurrentMenu(player);
                }
            }
        });
        menu.open(player);
    }

    public void openInbox(Player player) {
        boolean wasViewing = viewingInbox.contains(player.getUniqueId());
        viewingInbox.add(player.getUniqueId());
        if (wasViewing) {
            openingInbox.add(player.getUniqueId());
        }
        CrossPlatformMenu<String> menu = CrossUI.stringMenu(player)
                .title(INBOX_TITLE)
                .content(List.of("选择请求进行处理"));

        int slot = 0;
        for (TeleportRequest request : plugin.getLocalRequestsForTarget(player.getUniqueId())) {
            String requester = request.getRequesterName();
            menu.buttonAt(slot++, Material.PLAYER_HEAD,
                    requester,
                    List.of("§7剩余时间: §e" + request.getRemainingSeconds() + " 秒",
                            "§7来源: §a当前服务器",
                            "§a左键接受",
                            "§c右键拒绝"),
                    LOCAL_PREFIX + request.getRequesterUuid());
        }
        for (RemoteTeleportRequest request : plugin.getRemoteRequests(player.getUniqueId())) {
            String requester = request.getRequesterName();
            menu.buttonAt(slot++, Material.ENDER_EYE,
                    requester,
                    List.of("§7剩余时间: §e跨服请求",
                            "§7来源: §b其他服务器",
                            "§a左键接受",
                            "§c右键拒绝"),
                    REMOTE_PREFIX + request.getRequesterUuid());
        }

        if (slot == 0) {
            menu.buttonAt(13, Material.BARRIER, "当前没有待处理请求", "empty");
        }

        menu.onLeftClick(event -> handleInboxClick(player, event.getPayload(), true));
        menu.onRightClick(event -> handleInboxClick(player, event.getPayload(), false));
        menu.onBedrockClick(event -> openBedrockAction(player, event.getPayload()));
        menu.open(player);
    }

    public void refreshInboxIfViewing(Player player) {
        if (viewingInbox.contains(player.getUniqueId())) {
            openInbox(player);
        }
    }

    private void openBedrockAction(Player player, String payload) {
        if (!isRequestPayload(payload)) {
            return;
        }
        String requester = requesterFromPayload(payload);
        CrossPlatformMenu<String> menu = CrossUI.stringMenu(player)
                .title("处理传送请求")
                .content(List.of("处理该传送请求"));
        menu.buttonAt(11, Material.LIME_WOOL, "接受", "accept:" + requester);
        menu.buttonAt(15, Material.RED_WOOL, "拒绝", "deny:" + requester);
        menu.onClick(event -> {
            String action = event.getPayload();
            if (action == null) {
                return;
            }
            if (action.startsWith("accept:")) {
                if (plugin.acceptRequest(player, action.substring("accept:".length()))) {
                    closeCurrentMenu(player);
                }
            } else if (action.startsWith("deny:")) {
                if (plugin.denyRequest(player, action.substring("deny:".length()))) {
                    closeCurrentMenu(player);
                }
            }
        });
        menu.open(player);
    }

    private void handleInboxClick(Player player, String payload, boolean accept) {
        if (!isRequestPayload(payload)) {
            return;
        }
        String requester = requesterFromPayload(payload);
        boolean handled;
        if (accept) {
            handled = plugin.acceptRequest(player, requester);
        } else {
            handled = plugin.denyRequest(player, requester);
        }
        if (handled) {
            closeCurrentMenu(player);
        }
    }

    private boolean isRequestPayload(String payload) {
        return payload != null && (payload.startsWith(LOCAL_PREFIX) || payload.startsWith(REMOTE_PREFIX));
    }

    private String requesterFromPayload(String payload) {
        if (payload.startsWith(LOCAL_PREFIX)) {
            return payload.substring(LOCAL_PREFIX.length());
        }
        return payload.substring(REMOTE_PREFIX.length());
    }

    private void openManualInput(Player player) {
        viewingInbox.remove(player.getUniqueId());
        player.closeInventory();
        if (PlatformAPI.isBedrockPlayer(player)) {
            ElementsForm.builder()
                    .title("申请传送")
                    .label("请输入目标玩家名")
                    .input("目标玩家名", "", value ->
                            Bukkit.getScheduler().runTask(plugin, () -> submitManualTarget(player, value)))
                    .open(player);
            return;
        }

        MessageUtil.notifyInfo(player, "请输入玩家名", "请在告示牌第一行输入目标玩家名");
        SignGUIUtil.open(player, lines -> {
            String value = lines.length > 0 ? lines[0] : "";
            Bukkit.getScheduler().runTask(plugin, () -> submitManualTarget(player, value));
        }, "", "", "输入玩家名", "");
    }

    private void submitManualTarget(Player player, String value) {
        if (!player.isOnline()) {
            return;
        }
        String target = value == null ? "" : value.trim();
        if (target.isEmpty()) {
            MessageUtil.notifyError(player, "输入无效", "目标玩家名不能为空");
            return;
        }
        if (plugin.sendRequest(player, target)) {
            closeCurrentMenu(player);
        }
    }

    private void closeCurrentMenu(Player player) {
        viewingInbox.remove(player.getUniqueId());
        openingInbox.remove(player.getUniqueId());
        player.closeInventory();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (INBOX_TITLE.equals(event.getView().getTitle()) && event.getPlayer() instanceof Player player) {
            if (openingInbox.remove(player.getUniqueId())) {
                return;
            }
            viewingInbox.remove(player.getUniqueId());
        }
    }
}
