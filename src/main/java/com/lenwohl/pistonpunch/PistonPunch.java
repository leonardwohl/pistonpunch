package com.lenwohl.pistonpunch;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mod("pistonpunch")
public class PistonPunch {

    private static final Logger log = LogManager.getLogger();

    private DamageSource lastDamageSource;

    public PistonPunch() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        log.info("Piston punching is enabled!");
    }

    @SubscribeEvent
    public void onPunchBlock(PlayerInteractEvent.LeftClickBlock playerInteractEvent){
        if(playerInteractEvent.getItemStack().is(Items.PISTON)){
            playerInteractEvent.setUseItem(Event.Result.DENY);
            Direction face = playerInteractEvent.getFace();
            BlockPos pos = playerInteractEvent.getPos();
            Level world = playerInteractEvent.getWorld();
            if(face == null || pos == null || world == null)
                return;
            moveBlocks(world, pos, face.getOpposite());
            log.debug("piston punched at block {} {}", pos, face);
        }
    }

    @SubscribeEvent
    public void bufferLivingAttackEvent(LivingAttackEvent event) {
        lastDamageSource = event.getSource();
    }

    @SubscribeEvent
    public void onKnockbackEvent(LivingKnockBackEvent event) {
        if (lastDamageSource == null)
            return;
        Entity entity = lastDamageSource.getDirectEntity();

        if (!(entity instanceof Player player))
            return;

        if (player.getMainHandItem().is(Items.PISTON)) {
            event.setStrength(event.getStrength() + 1);
            return;
        }
        if (player.getMainHandItem().is(Items.STICKY_PISTON)){
            event.setStrength(0);
        }
    }


    // mostly copied from PistonBaseBlock.moveBlocks()
    private boolean moveBlocks(Level world, BlockPos pos, Direction direction) {
        PistonStructureResolver structure = new PistonStructureResolver(world, pos.offset(direction.getOpposite().getNormal()), direction, true);
        if (!structure.resolve()) {
            return false;
        }

        List<BlockPos> toPush = structure.getToPush();
        List<BlockState> statesToPush = toPush.stream().map(world::getBlockState).toList();
        Map<BlockPos, BlockState> positionToState = toPush.stream().collect(Collectors.toMap(k->k, world::getBlockState));

        List<BlockPos> toDestroy = structure.getToDestroy();
        BlockState[] blockStates = new BlockState[toPush.size() + toDestroy.size()];

        int j = 0;

        for (int i = toDestroy.size() - 1; i >= 0; --i) {
            BlockPos blockPos = toDestroy.get(i);
            BlockState blockState = world.getBlockState(blockPos);
            BlockEntity blockEntity = blockState.hasBlockEntity() ? world.getBlockEntity(blockPos) : null;
            Block.dropResources(blockState, world, blockPos, blockEntity);
            world.setBlock(blockPos, Blocks.AIR.defaultBlockState(), 18);
            if (!blockState.is(BlockTags.FIRE)) {
                world.addDestroyBlockEffect(blockPos, blockState);
            }
            blockStates[j++] = blockState;
        }

        for (int i = toPush.size() - 1; i >= 0; --i) {
            BlockPos blockPos = toPush.get(i);
            BlockState blockState = world.getBlockState(blockPos);
            blockPos = blockPos.relative(direction);
            positionToState.remove(blockPos);
            BlockState movingPiston = Blocks.MOVING_PISTON.defaultBlockState().setValue(DirectionalBlock.FACING, direction);
            world.setBlock(blockPos, movingPiston, 68);
            world.setBlockEntity(MovingPistonBlock.newMovingBlockEntity(blockPos, movingPiston, statesToPush.get(i), direction, true, false));
            blockStates[j++] = blockState;
        }

        BlockState air = Blocks.AIR.defaultBlockState();

        for (BlockPos blockPos : positionToState.keySet()) {
            world.setBlock(blockPos, air, 82);
        }

        for (BlockPos blockPos : positionToState.keySet()) {
            BlockState blockState = positionToState.get(blockPos);
            blockState.updateIndirectNeighbourShapes(world, blockPos, 2);
            air.updateNeighbourShapes(world, blockPos, 2);
            air.updateIndirectNeighbourShapes(world, blockPos, 2);
        }

        j = 0;

        for (int i = toDestroy.size() - 1; i >= 0; --i) {
            BlockState blockStateToUpdate = blockStates[j++];
            BlockPos blockToUpdate = toDestroy.get(i);
            blockStateToUpdate.updateIndirectNeighbourShapes(world, blockToUpdate, 2);
            world.updateNeighborsAt(blockToUpdate, blockStateToUpdate.getBlock());
        }

        for (int i = toPush.size() - 1; i >= 0; --i) {
            world.updateNeighborsAt(toPush.get(i), blockStates[j++].getBlock());
        }

        world.playSound(null, pos, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.5F, world.random.nextFloat() * 0.15F + 0.6F);
        world.gameEvent(GameEvent.PISTON_EXTEND, pos);
        return true;
    }
}
