package cn.blaze.hwidmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 客户端入口: 注册 blaze:hwid 载荷, 进服后异步采集机器码并上报。
 * 载荷 = 机器码 + 可选 "MODS:" 段 (本机 Fabric 模组 id 列表, 供服务端黑客端识别)。
 * 老服务端只按哈希解析, 多余段自动忽略, 双向兼容。
 * MC 26.x 去混淆后使用 Mojang 官方映射名 (Minecraft / getConnection)。
 */
public class BlazeHwidModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(HwidPayload.TYPE, HwidPayload.CODEC);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                HwidCollector.request().whenComplete((hwid, err) -> {
                    if (hwid != null) {
                        client.execute(() -> send(hwid, client));
                    }
                }));
    }

    private static void send(String hwid, Minecraft client) {
        if (hwid == null || client.getConnection() == null) {
            return;
        }
        ClientPlayNetworking.send(new HwidPayload(payload(hwid)));
    }

    /** 机器码 + 换行 + MODS:模组id列表 (排序保证稳定; 采集失败时只发机器码, 不影响上报)。 */
    private static String payload(String hwid) {
        StringBuilder sb = new StringBuilder(hwid);
        try {
            List<String> ids = new ArrayList<>();
            for (net.fabricmc.loader.api.ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                String id = mod.getMetadata().getId();
                if (id != null && !id.isBlank()) {
                    ids.add(id.trim());
                }
            }
            Collections.sort(ids);
            if (!ids.isEmpty()) {
                sb.append("\nMODS:").append(String.join(",", ids));
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }
}
