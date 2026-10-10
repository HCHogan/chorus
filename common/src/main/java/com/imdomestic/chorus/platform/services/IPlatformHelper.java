package com.imdomestic.chorus.platform.services;

public interface IPlatformHelper {
    void sendEquipmentView(net.minecraft.server.level.ServerPlayer player, com.imdomestic.chorus.network.EquipmentPayloads.View view);
    void sendMovementInput(net.minecraft.server.level.ServerPlayer player, com.imdomestic.chorus.network.MovementInputPayload payload);

    void sendHorizontalSpeed(net.minecraft.server.level.ServerPlayer player, com.imdomestic.chorus.network.HorizontalSpeedPayload payload);

    /** Join the loader's death-drop collection, without starting a player toss transaction. */
    default net.minecraft.world.entity.item.ItemEntity dropEquipmentOnDeath(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.item.ItemStack stack) {
        return player.drop(stack, true, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    /**
     * Gets the name of the current platform
     *
     * @return The name of the current platform.
     */
    String getPlatformName();

    /**
     * Checks if a mod with the given id is loaded.
     *
     * @param modId The mod to check if it is loaded.
     * @return True if the mod is loaded, false otherwise.
     */
    boolean isModLoaded(String modId);

    /**
     * Check if the game is currently in a development environment.
     *
     * @return True if in a development environment, false otherwise.
     */
    boolean isDevelopmentEnvironment();

    /**
     * Gets the name of the environment type as a string.
     *
     * @return The name of the environment type.
     */
    default String getEnvironmentName() {

        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
