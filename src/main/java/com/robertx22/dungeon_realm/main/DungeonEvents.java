package com.robertx22.dungeon_realm.main;

import com.robertx22.dungeon_realm.capability.DungeonEntityCapability;
import com.robertx22.dungeon_realm.configs.DungeonConfig;
import com.robertx22.dungeon_realm.database.holders.DungeonMapBlocks;
import com.robertx22.dungeon_realm.database.holders.DungeonRelicStats;
import com.robertx22.dungeon_realm.item.DungeonMapGenSettings;
import com.robertx22.dungeon_realm.item.DungeonMapItem;
import com.robertx22.dungeon_realm.item.relic.RelicGenerator;
import com.robertx22.dungeon_realm.structure.DungeonMapCapability;
import com.robertx22.dungeon_realm.structure.DungeonMapData;
import com.robertx22.library_of_exile.components.LibMapCap;
import com.robertx22.library_of_exile.dimension.MapDimensions;
import com.robertx22.library_of_exile.dimension.structure.dungeon.BuiltDungeon;
import com.robertx22.library_of_exile.dimension.structure.dungeon.BuiltRoom;
import com.robertx22.library_of_exile.events.base.EventConsumer;
import com.robertx22.library_of_exile.events.base.ExileEvents;
import com.robertx22.library_of_exile.main.ApiForgeEvents;
import com.robertx22.library_of_exile.main.ExileLog;
import com.robertx22.library_of_exile.util.PointData;
import com.robertx22.library_of_exile.utils.RandomUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.List;
import java.util.Optional;

public class DungeonEvents {

    public static void init() {

        // this way other mods can grab lib data without requiring dungeon realm as a dep. my head hurts
        ExileEvents.GRAB_LIB_MAP_DATA.register(new EventConsumer<ExileEvents.GrabLibMapData>() {
            @Override
            public void accept(ExileEvents.GrabLibMapData event) {
                DungeonMain.ifMapData(event.level, event.pos).ifPresent(x -> {
                    var cap = LibMapCap.get(event.level).data;
                    event.data = cap.getData(DungeonMain.MAIN_DUNGEON_STRUCTURE, event.pos);
                });
            }
        });


        ApiForgeEvents.registerForgeEvent(LivingDeathEvent.class, event -> {
            if (event.getEntity().level().isClientSide) {
                return;
            }
            LivingEntity mob = event.getEntity();

            if (MapDimensions.isMap(mob.level())) {

                if (DungeonEntityCapability.get(mob).data.isFinalMapBoss) {
                    if (MapDimensions.isMap(mob.level())) {
                        mob.level().setBlock(mob.blockPosition(), DungeonEntries.REWARD_TELEPORT.get().defaultBlockState(), Block.UPDATE_ALL);
                        DungeonMain.REWARD_ROOM.generateManually((ServerLevel) mob.level(), mob.chunkPosition());
                        // todo drop another map
                    }
                }

                // precondition: `isDungeonMob` and `isDungeonEliteMob` should be distinct
                // both should not exist on the same mob
                if (DungeonEntityCapability.get(mob).data.isDungeonMob) {
                    DungeonMain.ifMapData(mob.level(), mob.blockPosition()).ifPresent(x -> {
                        x.mobKills++;
                        updateRarity(x, event, mob);
                    });
                }

                if (DungeonEntityCapability.get(mob).data.isDungeonEliteMob) {
                    DungeonMain.ifMapData(mob.level(), mob.blockPosition()).ifPresent(x -> {
                        x.eliteKills++;
                        updateRarity(x, event, mob);
                    });
                }

                if (DungeonEntityCapability.get(mob).data.isMiniBossMob) {
                    DungeonMain.ifMapData(mob.level(), mob.blockPosition()).ifPresent(x -> {
                        x.miniBossKills++;
                        updateRarity(x, event, mob);
                    });
                }

                // hm, 3 relics per uber and 1 per map boss is ok?
                if (DungeonEntityCapability.get(mob).data.isUberBoss) {
                    for (int i = 0; i < 3; i++) {
                        mob.spawnAtLocation(RelicGenerator.randomRelicItem(Optional.empty(), new RelicGenerator.Settings()));
                    }
                }

                if (DungeonEntityCapability.get(mob).data.isFinalMapBoss) {

                    mob.spawnAtLocation(RelicGenerator.randomRelicItem(Optional.empty(), new RelicGenerator.Settings()));

                    var data = LibMapCap.getData(mob.level(), mob.blockPosition());

                    float chance = DungeonConfig.get().UBER_FRAG_DROPRATE.get().floatValue();

                    if (data != null) {
                        chance *= 1F + (data.relicStats.get(DungeonRelicStats.INSTANCE.BONUS_BOSS_FRAG_CHANCE.get()) / 100F);
                    }

                    if (RandomUtils.roll(chance)) {
                        mob.spawnAtLocation(DungeonEntries.UBER_FRAGMENT.get().getDefaultInstance());
                    }

                    // todo this isn't ideal
                    if (event.getSource().getEntity() instanceof Player p) {
                        var libdata = LibMapCap.getData(mob.level(), mob.blockPosition());
                        if (libdata != null) {
                            float mapchance = libdata.relicStats.get(DungeonRelicStats.INSTANCE.BONUS_MAP_ITEM_FROM_BOSS_CHANCE);
                            if (RandomUtils.roll(mapchance)) {
                                mob.spawnAtLocation(DungeonMapItem.newRandomMapItemStack(new DungeonMapGenSettings()));
                            }
                        }
                    }

                }
            }
        });

        ExileEvents.DUNGEON_DATA_BLOCK_PLACED.register(new EventConsumer<>() {
            @Override
            public void accept(ExileEvents.DungeonDataBlockPlaced event) {
                var blockNbt = event.blockInfo.nbt();
                if (blockNbt == null) {
                    ExileLog.get().warn("Dungeon Data Block NBT is null");
                    return;
                }
                String blockMetadata = getBlockMetadata(blockNbt);

                var serverLevel = event.levelAccessor.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, DungeonMain.DIMENSION_KEY));
                if (DungeonMain.MAP.isInside(DungeonMain.MAIN_DUNGEON_STRUCTURE, serverLevel, event.pos)) {
                    DungeonMain.ifMapData(serverLevel, event.pos).ifPresent(mapData -> {
                        var mobSpawnBlockKind = DungeonMapBlocks.getMobSpawnBlockKindFromBlockMetadata(blockMetadata);
                        mobSpawnBlockKind.ifPresent(mapData::incrementSpawnBlockCountByKind);
                    });
                }
            }
        });


        ExileEvents.ON_CHEST_LOOTED.register(new EventConsumer<>() {
            @Override
            public void accept(ExileEvents.OnChestLooted event) {
                if (event.player.level().isClientSide) {
                    return;
                }
                if (MapDimensions.isMap(event.player.level())) {
                    DungeonMain.ifMapData(event.player.level(), event.pos).ifPresent(x -> {
                        x.lootedChests++;
                        if (event.player instanceof ServerPlayer sp) {
                            x.updateMapCompletionRarity(sp);
                        }
                    });
                }
            }
        });


        ExileEvents.PROCESS_CHUNK_DATA.register(new EventConsumer<>() {
            @Override
            public void accept(ExileEvents.OnProcessChunkData event) {
                if (event.struc.guid().equals(DungeonMain.MAIN_DUNGEON_STRUCTURE.guid())) {
                    DungeonMain.ifMapData(event.p.level(), event.cp.getMiddleBlockPosition(5)).ifPresent(x -> {
                        x.bonusContents.processedChunks++;
                        if (x.bonusContents.totalGenDungeonChunks < 1) {
                            var built = DungeonMain.MAIN_DUNGEON_STRUCTURE.getMap(event.cp);
                            built.build();
                            x.bonusContents.totalGenDungeonChunks = built.builtDungeon.amount;
                            x.totalChests = countMapChests(event.p.level().getServer().getStructureManager(), built.builtDungeon);
                        }
                    });
                }
            }
        });

        ExileEvents.PROCESS_DATA_BLOCK.register(new EventConsumer<>() {
            @Override
            public void accept(ExileEvents.OnProcessMapDataBlock event) {
                if (event.dataBlock.tags.contains(DataBlockTags.CAN_SPAWN_LEAGUE)) {
                    trySpawnLeagueMechanicIfCan(event.world, event.pos);
                }
            }
        });
    }

    private static void updateRarity(DungeonMapData x, LivingDeathEvent event, LivingEntity mob) {
        if (event.getSource().getEntity() instanceof ServerPlayer sp) {
            x.updateMapCompletionRarity(sp);
        } else {
            var players = DungeonMain.MAIN_DUNGEON_STRUCTURE.getAllPlayersInMap(mob.level(), mob.blockPosition());
            if (!players.isEmpty() && players.get(0) instanceof ServerPlayer sp) {
                x.updateMapCompletionRarity(sp);
            }
        }
    }

    private static int countMapChests(StructureTemplateManager templateManager, BuiltDungeon dungeon) {
        int count = 0;
        for (BuiltRoom[] row : dungeon.getRooms()) {
            for (BuiltRoom room : row) {
                if (room == null || room.room.isBarrier) {
                    continue;
                }
                var template = templateManager.get(room.getStructure()).orElse(null);
                if (template == null) {
                    continue;
                }
                count += countMapChests(template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.COMMAND_BLOCK, true));
                count += countMapChests(template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.STRUCTURE_BLOCK, true));
            }
        }
        return count;
    }

    private static int countMapChests(List<StructureTemplate.StructureBlockInfo> blocks) {
        int count = 0;
        for (StructureTemplate.StructureBlockInfo block : blocks) {
            if (block.nbt() != null && DungeonMapBlocks.INSTANCE.MAP_CHEST.get().matches(getBlockMetadata(block.nbt()), block.pos(), null, block.nbt())) {
                count++;
            }
        }
        return count;
    }

    private static String getBlockMetadata(net.minecraft.nbt.CompoundTag blockNbt) {
        if (blockNbt.contains("metadata")) {
            return blockNbt.getString("metadata");
        }
        if (blockNbt.contains("Command")) {
            return blockNbt.getString("Command");
        }
        return "unknown";
    }

    // we're only spawning bonus content in the main map dim+structure
    public static void trySpawnLeagueMechanicIfCan(Level world, BlockPos pos) {
        if (DungeonMain.MAP.isInside(DungeonMain.MAIN_DUNGEON_STRUCTURE, (ServerLevel) world, pos)) {
            var data = DungeonMapCapability.get(world).data.data.getData(DungeonMain.MAIN_DUNGEON_STRUCTURE, pos);
            if (data != null) {
                float chance = data.bonusContents.calcSpawnChance(pos);

                if (DungeonMain.RUN_DEV_TOOLS) {
                    System.out.println("trySpawnLeagueMechanicIfCan: Pos=" + pos + " Chance=" + chance + " totalGen=" + data.bonusContents.totalGenDungeonChunks + " processed=" + data.bonusContents.processedChunks);
                }

                if (RandomUtils.roll(chance)) {
                    if (DungeonMain.RUN_DEV_TOOLS) {
                        System.out.println("trySpawnLeagueMechanicIfCan: Spawning bonus content!");
                    }
                    data.spawnBonusMapContent(world, pos);
                }
                var cp = new ChunkPos(pos);
                var point = new PointData(cp.x, cp.z);
                data.bonusContents.mechsChunks.add(point);
            } else if (DungeonMain.RUN_DEV_TOOLS) {
                System.out.println("trySpawnLeagueMechanicIfCan: DungeonMapData is NULL at " + pos);
            }
        }
    }

}
