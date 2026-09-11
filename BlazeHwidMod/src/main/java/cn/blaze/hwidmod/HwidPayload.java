package cn.blaze.hwidmod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 -> 服务端 的机器码上报包。
 * 频道 blaze:hwid, 内容为 64 位十六进制 SHA-256。
 * MC 26.x 官方映射: Identifier / CustomPacketPayload / StreamCodec.composite。
 */
public record HwidPayload(String hwid) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<HwidPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("blaze", "hwid"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HwidPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, HwidPayload::hwid, HwidPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
