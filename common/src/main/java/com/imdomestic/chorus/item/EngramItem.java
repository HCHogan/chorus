package com.imdomestic.chorus.item;

import com.imdomestic.chorus.Constants;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

public class EngramItem extends Item {
    public EngramItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Constants.LOG.info("EngramItem.use 运行在 {}（线程：{}）",
                level.isClientSide() ? "客户端" : "服务端", Thread.currentThread().getName());

        if (level instanceof ServerLevel serverLevel) {
            ResourceKey<LootTable> tableKey = ResourceKey.create(Registries.LOOT_TABLE,
                    Identifier.fromNamespaceAndPath(Constants.MOD_ID, "engram"));
            LootTable table = serverLevel.getServer().reloadableRegistries().getLootTable(tableKey);
            LootParams params = new LootParams.Builder(serverLevel)
                    .withParameter(LootContextParams.ORIGIN, player.position())
                    .withParameter(LootContextParams.THIS_ENTITY, player)
                    .create(LootContextParamSets.GIFT);

            for (ItemStack reward : table.getRandomItems(params, serverLevel.getRandom())) {
                Component rewardName = reward.getHoverName();
                player.getInventory().placeItemBackInInventory(reward, Prediction.SERVER_ONLY);
                player.sendOverlayMessage(Component.translatable("item.chorus.engram.decrypted", rewardName));
                serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.0F);
            }
        }

        stack.consume(1, player);
        return InteractionResult.SUCCESS;
    }
}
