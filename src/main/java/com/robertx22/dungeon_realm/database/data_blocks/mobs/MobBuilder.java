package com.robertx22.dungeon_realm.database.data_blocks.mobs;

import com.robertx22.dungeon_realm.api.DungeonExileEvents;
import com.robertx22.dungeon_realm.api.DungeonMobSpawnedEvent;
import com.robertx22.dungeon_realm.api.PrepareDungeonMobEditsEvent;
import com.robertx22.dungeon_realm.capability.DungeonEntityCapability;
import com.robertx22.dungeon_realm.capability.DungeonEntityData;
import com.robertx22.dungeon_realm.main.DungeonMain;
import com.robertx22.library_of_exile.database.map_data_block.MapDataBlock;
import com.robertx22.library_of_exile.utils.geometry.MyPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class MobBuilder {

    private EntityType type;
    public int amount = 1;
    public DungeonEntityData mobEntityData;

    public Optional<MapDataBlock> dataBlock;
    public List<Consumer<LivingEntity>> mobEdits = new ArrayList<>();

    private MobBuilder() {
    }

    public static MobBuilder of(EntityType type, MapDataBlock block, Consumer<MobBuilder> co) {
        MobBuilder b = new MobBuilder();
        b.type = type;
        b.dataBlock = Optional.ofNullable(block);
        co.accept(b);
        return b;
    }

    public <T extends LivingEntity> List<T> summonMobs(Level world, BlockPos p) {
        List<T> list = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            T mob = (T) summon(type, world, p);
            list.add(mob);
        }

        DungeonMain.ifMapData(world, p).ifPresent(x -> {
            if(this.mobEntityData.isDungeonMob) {
                x.mobSpawnCount += amount;
                if(this.mobEntityData.isPackMob) {
                    x.processedPackDataBlockCount++;
                } else {
                    x.processedMobDataBlockCount++;
                }
            } else if (this.mobEntityData.isDungeonEliteMob) {
                x.eliteSpawnCount += amount;
                if(this.mobEntityData.isPackMob) {
                    x.processedElitePackDataBlockCount++;
                } else {
                    x.processedEliteDataBlockCount++;
                }
            } else if (this.mobEntityData.isMiniBossMob) {
                x.miniBossSpawnCount += amount;
                x.processedMiniBossDataBlockCount++;
            }
        });

        return list;
    }

    private <T extends Mob> T summon(EntityType<T> type, Level world, BlockPos p) {
        T mob = (T) type.create(world);

        mob.finalizeSpawn((ServerLevelAccessor) world, world.getCurrentDifficultyAt(p), MobSpawnType.REINFORCEMENT, null);
        MyPosition vec = new MyPosition(getSafeSpawnPosition(mob, world, p));
        mob.setPos(vec.x(), vec.y(), vec.z());


        var prepare = new PrepareDungeonMobEditsEvent(mob, dataBlock);
        DungeonExileEvents.PREPARE_DUNGEON_MOB_SPAWN.callEvents(prepare);
        this.mobEdits.addAll(prepare.edits);

        // todo should i do edits before or after mob is spawned?

        world.addFreshEntity(mob);

        for (Consumer<LivingEntity> edit : this.mobEdits) {
            edit.accept(mob);
        }
        DungeonEntityCapability.get(mob).data = this.mobEntityData;

        var afterSpawn = new DungeonMobSpawnedEvent(mob);
        DungeonExileEvents.DUNGEON_MOB_SPAWNED.callEvents(afterSpawn);

        return mob;
    }

    private <T extends Mob> Vec3 getSafeSpawnPosition(T mob, Level world, BlockPos p) {
        BlockPos best = SpawnPointHelper.getBestSpawnPosition(world, p);
        Vec3 center = Vec3.atBottomCenterOf(best);
        if (canMobFit(mob, world, best, center)) {
            return center;
        }
        for (int radius = 1; radius <= 3; radius++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        BlockPos pos = best.offset(x, y, z);
                        Vec3 testCenter = Vec3.atBottomCenterOf(pos);
                        if (canMobFit(mob, world, pos, testCenter)) {
                            return testCenter;
                        }
                    }
                }
            }
        }
        return center;
    }

    private <T extends Mob> boolean canMobFit(T mob, Level world, BlockPos pos, Vec3 center) {
        if (world.getBlockState(pos.below()).getCollisionShape(world, pos.below()).isEmpty()) {
            return false;
        }
        AABB box = AABB.ofSize(center.add(0, mob.getBbHeight() / 2F, 0), mob.getBbWidth(), mob.getBbHeight(), mob.getBbWidth());
        return world.noBlockCollision(mob, box);
    }

}
