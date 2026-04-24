package com.tpor;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TporPlugin extends JavaPlugin implements CommandExecutor, Listener {

    private final Map<UUID, LockData> lockedPlayers = new HashMap<>();
    private final Map<UUID, Integer> freezeTasks = new HashMap<>();
    private BufferedImage paymentImage;

    // 反射相关字段
    private Class<?> craftPlayerClass;
    private Method getHandleMethod;
    private Field playerConnectionField;
    private Class<?> packetPlayOutSteerVehicleClass;
    private Object zeroSteerVehiclePacket; // 预构造的停止移动数据包

    @Override
    public void onEnable() {
        getCommand("tpor").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        loadPaymentImage();
        setupReflection();
        getLogger().info("Tpor插件已加载！");
    }

    private void setupReflection() {
        try {
            craftPlayerClass = Class.forName("org.bukkit.craftbukkit.v1_12_R1.entity.CraftPlayer");
            getHandleMethod = craftPlayerClass.getMethod("getHandle");
            playerConnectionField = Class.forName("net.minecraft.server.v1_12_R1.EntityPlayer").getField("playerConnection");
            Class<?> packetClass = Class.forName("net.minecraft.server.v1_12_R1.Packet");
            packetPlayOutSteerVehicleClass = Class.forName("net.minecraft.server.v1_12_R1.PacketPlayOutSteerVehicle");
            // 创建一个“停止移动”的数据包 (sideways=0, forward=0, jump=false, unmount=false)
            zeroSteerVehiclePacket = packetPlayOutSteerVehicleClass.getConstructor(float.class, float.class, boolean.class, boolean.class)
                    .newInstance(0f, 0f, false, false);
            getLogger().info("反射初始化成功，将使用数据包强制锁定移动输入");
        } catch (Exception e) {
            getLogger().warning("反射初始化失败，将使用备用锁定方案: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        for (Integer taskId : freezeTasks.values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        freezeTasks.clear();
        for (UUID uuid : lockedPlayers.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) unlockPlayer(p);
        }
        lockedPlayers.clear();
        getLogger().info("Tpor插件已卸载！");
    }

    private void loadPaymentImage() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();
        File imageFile = new File(dataFolder, "tpor.png");
        if (!imageFile.exists()) {
            getLogger().warning("未找到图片文件，将生成默认占位图。");
            paymentImage = createDefaultQRPlaceholder();
            return;
        }
        try {
            BufferedImage original = ImageIO.read(imageFile);
            java.awt.Image scaled = original.getScaledInstance(128, 128, java.awt.Image.SCALE_SMOOTH);
            paymentImage = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = paymentImage.createGraphics();
            g.drawImage(scaled, 0, 0, null);
            g.dispose();
            getLogger().info("成功加载收款码图片");
        } catch (IOException e) {
            getLogger().severe("图片加载失败: " + e.getMessage());
            paymentImage = createDefaultQRPlaceholder();
        }
    }

    private BufferedImage createDefaultQRPlaceholder() {
        BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 128, 128);
        g.setColor(java.awt.Color.BLACK);
        g.setFont(new java.awt.Font("微软雅黑", java.awt.Font.BOLD, 12));
        g.drawString("收款码", 40, 60);
        g.drawString("请付款100元", 30, 80);
        g.drawString("(仅供娱乐)", 35, 100);
        g.dispose();
        return img;
    }

    private ItemStack createPaymentMap() {
        World world = Bukkit.getWorlds().get(0);
        if (world == null) return new ItemStack(Material.MAP);
        MapView mapView = Bukkit.createMap(world);
        if (mapView == null) return new ItemStack(Material.MAP);
        try {
            Field renderersField = mapView.getClass().getDeclaredField("renderers");
            renderersField.setAccessible(true);
            List<MapRenderer> renderers = (List<MapRenderer>) renderersField.get(mapView);
            renderers.clear();
        } catch (Exception e) {
            getLogger().warning("无法清除默认地图渲染器");
        }
        mapView.addRenderer(new MapRenderer() {
            private boolean rendered = false;
            @Override
            public void render(MapView map, MapCanvas canvas, Player player) {
                if (rendered) return;
                if (canvas == null) return;
                canvas.drawImage(0, 0, paymentImage);
                rendered = true;
            }
        });
        ItemStack mapItem = new ItemStack(Material.MAP, 1);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "【收款码】请扫码付款");
            meta.setLore(java.util.Arrays.asList(ChatColor.GRAY + "收款方: 恶意整蛊服务器", ChatColor.RED + "金额: 100元"));
            try {
                Field mapViewField = MapMeta.class.getDeclaredField("mapView");
                mapViewField.setAccessible(true);
                mapViewField.set(meta, mapView);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                mapItem.setDurability(mapView.getId());
            }
            mapItem.setItemMeta(meta);
        } else {
            mapItem.setDurability(mapView.getId());
        }
        return mapItem;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("该命令只能由玩家执行。");
            return true;
        }
        if (!sender.hasPermission("tpor.use")) {
            sender.sendMessage(ChatColor.RED + "杂鱼没权限还想用插件~杂鱼~");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "用法: /tpor <玩家名>");
            return true;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "玩家不在线或不存在！");
            return true;
        }
        if (lockedPlayers.containsKey(target.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "该玩家已经被锁定！");
            return true;
        }
        lockPlayer(target);
        sender.sendMessage(ChatColor.GREEN + "已锁定玩家 " + target.getName() + "，10秒后将清空其背包！");
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!target.isOnline()) {
                    lockedPlayers.remove(target.getUniqueId());
                    return;
                }
                target.getInventory().clear();
                target.getInventory().setHelmet(null);
                target.getInventory().setChestplate(null);
                target.getInventory().setLeggings(null);
                target.getInventory().setBoots(null);
                target.getInventory().setItemInOffHand(null);
                sendBigTitle(target, ChatColor.RED + "刚刚服务器有10秒延迟", ChatColor.YELLOW + "你的物品已被清空~");
                target.sendMessage(ChatColor.RED + "刚刚服务器有10秒延迟");
                unlockPlayer(target);
            }
        }.runTaskLater(this, 20 * 10);
        return true;
    }

    private void lockPlayer(Player player) {
        Location originalLoc = player.getLocation().clone();
        boolean originalInvulnerable = player.isInvulnerable();
        float originalWalkSpeed = player.getWalkSpeed();
        float originalFlySpeed = player.getFlySpeed();

        LockData data = new LockData(originalLoc, originalInvulnerable, originalWalkSpeed, originalFlySpeed);
        lockedPlayers.put(player.getUniqueId(), data);

        // 基础锁定
        player.setWalkSpeed(0.0f);
        player.setFlySpeed(0.0f);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(true);
        player.setFallDistance(0);
        player.setVelocity(new Vector(0, 0, 0));

        // 副手地图
        player.getInventory().setItemInOffHand(createPaymentMap());

        // 显示警告
        player.sendTitle(ChatColor.RED + "10秒不付款自动毁号", ChatColor.YELLOW + "请尽快付款！", 10, 180, 20);
        player.sendMessage(ChatColor.RED + "10秒不付款自动毁号");

        // 启动强力冻结任务（每 tick 强制恢复位置、朝向、清除移动输入）
        int taskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (!lockedPlayers.containsKey(player.getUniqueId())) {
                    this.cancel();
                    return;
                }
                Location targetLoc = data.originalLocation.clone();
                targetLoc.setYaw(data.originalYaw);
                targetLoc.setPitch(data.originalPitch);
                player.teleport(targetLoc);
                player.teleport(targetLoc);

                // 2. 清空速度
                player.setVelocity(new Vector(0, 0, 0));
                player.setFallDistance(0);
                player.setWalkSpeed(0.0f);
                player.setFlySpeed(0.0f);
                sendStopMovementPacket(player);
            }
        }.runTaskTimer(this, 0L, 1L).getTaskId();
        freezeTasks.put(player.getUniqueId(), taskId);
    }
    private void sendStopMovementPacket(Player player) {
        if (zeroSteerVehiclePacket == null) return;
        try {
            Object nmsPlayer = getHandleMethod.invoke(craftPlayerClass.cast(player));
            Object connection = playerConnectionField.get(nmsPlayer);
            Method sendPacketMethod = connection.getClass().getMethod("sendPacket", packetPlayOutSteerVehicleClass);
            sendPacketMethod.invoke(connection, zeroSteerVehiclePacket);
        } catch (Exception e) {
        }
    }

    private void unlockPlayer(Player player) {
        Integer taskId = freezeTasks.remove(player.getUniqueId());
        if (taskId != null) Bukkit.getScheduler().cancelTask(taskId);

        LockData data = lockedPlayers.remove(player.getUniqueId());
        if (data == null) return;
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setInvulnerable(data.originalInvulnerable);
        player.setWalkSpeed(0.0f);
        player.setWalkSpeed(data.originalWalkSpeed);
        player.setFlySpeed(0.0f);
        player.setFlySpeed(data.originalFlySpeed);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.teleport(data.originalLocation);
        player.updateInventory();
        player.sendMessage("");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                Location loc = player.getLocation();
                loc.setYaw(data.originalYaw);
                loc.setPitch(data.originalPitch);
                player.teleport(loc);
                player.setWalkSpeed(data.originalWalkSpeed);
            }
        }, 1L);
    }

    private void sendBigTitle(Player player, String title, String subtitle) {
        player.sendTitle(title, subtitle, 10, 70, 20);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        LockData data = lockedPlayers.get(player.getUniqueId());
        if (data == null) return;
        event.setTo(event.getFrom());
        player.teleport(data.originalLocation);
        player.setVelocity(new Vector(0, 0, 0));
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (lockedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private static class LockData {
        final Location originalLocation;
        final boolean originalInvulnerable;
        final float originalWalkSpeed;
        final float originalFlySpeed;
        final float originalYaw;
        final float originalPitch;

        LockData(Location loc, boolean invuln, float walkSpeed, float flySpeed) {
            this.originalLocation = loc.clone();
            this.originalInvulnerable = invuln;
            this.originalWalkSpeed = walkSpeed;
            this.originalFlySpeed = flySpeed;
            this.originalYaw = loc.getYaw();
            this.originalPitch = loc.getPitch();
        }
    }
}