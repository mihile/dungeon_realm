package com.robertx22.dungeon_realm.structure;

import com.google.gson.JsonSyntaxException;
import com.robertx22.dungeon_realm.main.DungeonMain;
import com.robertx22.library_of_exile.dimension.MapDataFinder;
import com.robertx22.library_of_exile.dimension.MapDimensionInfo;
import com.robertx22.library_of_exile.utils.LoadSave;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import com.robertx22.library_of_exile.compat.capability.Capability;
import com.robertx22.library_of_exile.compat.capability.CapabilityManager;
import com.robertx22.library_of_exile.compat.capability.CapabilityToken;
import com.robertx22.library_of_exile.compat.capability.ICapabilitySerializable;
import com.robertx22.library_of_exile.compat.capability.LazyOptional;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DungeonMapCapability implements ICapabilitySerializable<CompoundTag>, INBTSerializable<CompoundTag> {

    public Level world;
    
    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        return serializeNBT();
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
        deserializeNBT(nbt);
    }

    public static final ResourceLocation RESOURCE = ResourceLocation.fromNamespaceAndPath(DungeonMain.MODID, "world_data");
    public static Capability<DungeonMapCapability> INSTANCE = CapabilityManager.get(new CapabilityToken<>() {
    });

    public static DungeonMapCapability get(Level entity) {
        if (entity instanceof net.minecraft.server.level.ServerLevel sl) {
            return sl.getServer().overworld().getData(com.robertx22.dungeon_realm.main.DungeonEntries.WORLD_DATA.get()).init(entity);
        }
        return entity.getData(com.robertx22.dungeon_realm.main.DungeonEntries.WORLD_DATA.get()).init(entity);
    }

    public DungeonMapCapability init(Level entity) {
        this.world = entity;
        return this;
    }

    transient final LazyOptional<DungeonMapCapability> supp = LazyOptional.of(() -> this);

    public static DungeonMapCapability getFromServer() {
        return get(ServerLifecycleHooks.getCurrentServer().overworld());
    }


    public DungeonWorldData data = new DungeonWorldData();

    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == INSTANCE) {
            return supp.cast();
        }
        return LazyOptional.empty();

    }

    @Override
    public CompoundTag serializeNBT() {
        var nbt = new CompoundTag();

        try {

            LoadSave.Save(data, nbt, "data");

        } catch (Exception e) {
            e.printStackTrace();
        }


        return nbt;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {

        try {
            this.data = LoadSave.loadOrBlank(DungeonWorldData.class, new DungeonWorldData(), nbt, "data", new DungeonWorldData());


        } catch (JsonSyntaxException e) {
            e.printStackTrace();
        }

    }

    public static MapDataFinder<DungeonMapData> DATA_GETTER = new MapDataFinder<>() {
        @Override
        public DungeonMapData getData(Pos pos) {
            return get(pos.level).data.data.getData(this.getInfo().structure, pos.pos);
        }

        @Override
        public MapDimensionInfo getInfo() {
            return DungeonMain.MAP;
        }

    };
}
