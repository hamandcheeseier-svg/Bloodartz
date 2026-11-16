package com.example.bloodarts;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class BloodArts implements ModInitializer {
    public static final String MOD_ID = "bloodarts";
    public static final Item BLOOD_FOCUS = Registry.register(
            Registries.ITEM, Identifier.of(MOD_ID, "blood_focus"),
            new BloodFocusItem(new Item.Settings().maxCount(1)));

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(e -> e.add(BLOOD_FOCUS));
    }
}
