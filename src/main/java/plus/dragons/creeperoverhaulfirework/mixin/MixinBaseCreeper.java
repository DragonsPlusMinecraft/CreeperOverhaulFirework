package plus.dragons.creeperoverhaulfirework.mixin;

import com.google.common.collect.Sets;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import love.marblegate.creeperfirework.misc.Configuration;
import love.marblegate.creeperfirework.mixin.ExplosionMethodInvoker;
import love.marblegate.creeperfirework.network.Networking;
import love.marblegate.creeperfirework.network.Packet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ProtectionEnchantment;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import software.bernie.geckolib3.core.IAnimatable;
import tech.thatgravyboat.creeperoverhaul.common.entity.base.BaseCreeper;
import tech.thatgravyboat.creeperoverhaul.common.entity.base.CreeperType;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mixin(BaseCreeper.class)
public abstract class MixinBaseCreeper extends Creeper implements IAnimatable {

    @Final
    @Shadow(remap = false)
    public CreeperType type;

    protected MixinBaseCreeper(EntityType<? extends Creeper> p_33002_, Level p_33003_) {
        super(p_33002_, p_33003_);
    }

    @Inject(method = "explode", at = @At("HEAD"), cancellable = true, remap = false)
    private void injected(CallbackInfo ci) {
        var self = ((BaseCreeper) (Object) this);
        if (Configuration.ACTIVE_EXPLOSION_TO_FIREWORK.get() && new Random(self.getUUID().getLeastSignificantBits()).nextDouble() < Configuration.ACTIVE_EXPLOSION_TURNING_PROBABILITY.get()) {
            if (!self.getLevel().isClientSide()) {
                sendEffectPacket(self.getLevel(), self.blockPosition());
                Explosion.BlockInteraction interaction = ForgeEventFactory.getMobGriefingEvent(this.level, this) ? Explosion.BlockInteraction.DESTROY : Explosion.BlockInteraction.NONE;
                if (Configuration.ACTIVE_EXPLOSION_HURT_CREATURE.get())
                    simulateExplodeHurtMob();
                if (Configuration.ACTIVE_EXPLOSION_DESTROY_BLOCK.get() && self.getLevel().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING))
                    simulateExplodeDestroyBlock(interaction);
                spawnPotionCloud();
            }
            self.discard();
            ci.cancel();
        }

    }

    @Override
    public void die(DamageSource damageSource) {
        var self = ((BaseCreeper) (Object) this);
        // A compromise
        // super.die(damageSource);
        if (Configuration.DEATH_TO_FIREWORK.get() && new Random(self.getUUID().getLeastSignificantBits()).nextDouble() < Configuration.DEATH_EXPLOSION_TURNING_PROBABILITY.get()) {
            Explosion.BlockInteraction interaction = ForgeEventFactory.getMobGriefingEvent(this.level, this) ? Explosion.BlockInteraction.DESTROY : Explosion.BlockInteraction.NONE;
            if (!self.getLevel().isClientSide()) {
                sendEffectPacket(self.getLevel(), self.blockPosition());
                if (Configuration.DEATH_EXPLOSION_HURT_CREATURE.get())
                    simulateExplodeHurtMob();
                if (Configuration.DEATH_EXPLOSION_DESTROY_BLOCK.get() && self.getLevel().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING))
                    simulateExplodeDestroyBlock(interaction);
                spawnPotionCloud();
            }
        }
    }

    private void sendEffectPacket(Level level, BlockPos pos) {
        List<Player> players = level.players().stream().filter(serverPlayerEntity -> serverPlayerEntity.blockPosition().closerThan(pos, 192)).collect(Collectors.toList());
        for (Player player : players) {
            Networking.INSTANCE.send(
                    PacketDistributor.PLAYER.with(
                            () -> (ServerPlayer) player
                    ),
                    new Packet(pos, false));
        }
    }

    private void simulateExplodeHurtMob() {
        var self = ((BaseCreeper) (Object) this);
        Vec3 groundZero = self.position();
        AABB aabb = new AABB(self.blockPosition()).inflate(getExplosionPower());
        List<LivingEntity> victims = self.getLevel().getEntitiesOfClass(LivingEntity.class, aabb);
        for (LivingEntity victim : victims) {
            if (!victim.ignoreExplosion()) {
                float j = getExplosionPower() * 2.0F;
                double h = Math.sqrt(victim.distanceToSqr(groundZero)) / (double) j;
                if (h <= 1.0D) {
                    double s = victim.getX() - groundZero.x;
                    double t = victim.getEyeY() - groundZero.y;
                    double u = victim.getZ() - groundZero.z;
                    double blockPos = Math.sqrt(s * s + t * t + u * u);
                    if (blockPos != 0.0D) {
                        s /= blockPos;
                        t /= blockPos;
                        u /= blockPos;
                        double fluidState = Explosion.getSeenPercent(groundZero, victim);
                        double v = (1.0D - h) * fluidState;
                        victim.hurt(DamageSource.explosion(self), (float) ((int) ((v * v + v) / 2.0D * 7.0D * (double) j + 1.0D)));
                        double w = ProtectionEnchantment.getExplosionKnockbackAfterDampener((LivingEntity) victim, v);

                        victim.setDeltaMovement(victim.getDeltaMovement().add(s * w, t * w, u * w));
                    }
                }
            }
        }
        
        // Creeper Overhaul Part
        if (!type.inflictingPotions().isEmpty()) {
            var players = victims.stream().filter(livingEntity -> livingEntity instanceof Player).toList();
            players.forEach((player) -> {
                Collection<MobEffectInstance> inflictingPotions = type.inflictingPotions().stream().map(MobEffectInstance::new).toList();
                inflictingPotions.forEach(player::addEffect);
            });
        }
    }


    private void simulateExplodeDestroyBlock(Explosion.BlockInteraction interaction) {
        var self = ((BaseCreeper) (Object) this);
        self.getLevel().gameEvent(self, GameEvent.EXPLODE, self.blockPosition());
        Set<BlockPos> explosionRange = Sets.newHashSet();
        BlockPos groundZero = self.blockPosition();
        for (int j = 0; j < 16; ++j) {
            for (int k = 0; k < 16; ++k) {
                for (int l = 0; l < 16; ++l) {
                    if (j == 0 || j == 15 || k == 0 || k == 15 || l == 0 || l == 15) {
                        double d = (float) j / 15.0F * 2.0F - 1.0F;
                        double e = (float) k / 15.0F * 2.0F - 1.0F;
                        double f = (float) l / 15.0F * 2.0F - 1.0F;
                        double g = Math.sqrt(d * d + e * e + f * f);
                        d /= g;
                        e /= g;
                        f /= g;
                        float h = getExplosionPower() * (0.7F + self.getLevel().random.nextFloat() * 0.6F);
                        double m = groundZero.getX();
                        double n = groundZero.getY();
                        double o = groundZero.getZ();
                        for (; h > 0.0F; h -= 0.22500001F) {
                            BlockPos blockPos = new BlockPos(m, n, o);
                            BlockState blockState = self.getLevel().getBlockState(blockPos);
                            FluidState fluidState = self.getLevel().getFluidState(blockPos);
                            if (!self.getLevel().isInWorldBounds(blockPos)) {
                                break;
                            }

                            Optional<Float> optional = blockState.isAir() && fluidState.isEmpty() ? Optional.empty() : Optional.of(Math.max(blockState.getBlock().getExplosionResistance(), fluidState.getExplosionResistance()));
                            if (optional.isPresent()) {
                                h -= (optional.get() + 0.3F) * 0.3F;
                            }

                            if (h > 0.0F) {
                                explosionRange.add(blockPos);
                            }

                            m += d * 0.30000001192092896D;
                            n += e * 0.30000001192092896D;
                            o += f * 0.30000001192092896D;
                        }
                    }
                }
            }
        }

        if(interaction!= Explosion.BlockInteraction.NONE){

            ObjectArrayList<Pair<ItemStack, BlockPos>> blockDropList = new ObjectArrayList<>();

            /// I really do not want to create an explosion instance here. But there is a method below needs it.
            Explosion simulateExplosionForParameter = new Explosion(self.getLevel(), null, null, null,
                    self.getBlockX(), self.getBlockY(), self.getBlockZ(), getExplosionPower(), false, interaction);

            for (BlockPos affectedPos : explosionRange) {
                BlockState blockStateOfAffected = self.getLevel().getBlockState(affectedPos);
                if (!blockStateOfAffected.isAir()) {
                    BlockPos blockPos2 = affectedPos.immutable();
                    self.getLevel().getProfiler().push("explosion_blocks");

                    BlockEntity blockEntity = blockStateOfAffected.hasBlockEntity() ? self.getLevel().getBlockEntity(affectedPos) : null;
                    LootContext.Builder builder = (new LootContext.Builder((ServerLevel) self.getLevel())).withRandom(self.getLevel().random).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(affectedPos)).withParameter(LootContextParams.TOOL, ItemStack.EMPTY).withOptionalParameter(LootContextParams.BLOCK_ENTITY, blockEntity).withOptionalParameter(LootContextParams.THIS_ENTITY, ((Creeper) (Object) this));
                    builder.withParameter(LootContextParams.EXPLOSION_RADIUS, getExplosionPower());

                    blockStateOfAffected.getDrops(builder).forEach((stack) -> {
                        ExplosionMethodInvoker.invokeAddBlockDrops(blockDropList, stack, blockPos2);
                    });

                    self.getLevel().setBlock(affectedPos, Blocks.AIR.defaultBlockState(), 3);

                    // yes here is what I'm talking. This part cannot be deleted.
                    blockStateOfAffected.onBlockExploded(self.getLevel(), affectedPos, simulateExplosionForParameter);
                    self.getLevel().getProfiler().pop();
                }
            }

            for (Pair<ItemStack, BlockPos> itemStackBlockPosPair : blockDropList) {
                Block.popResource(self.getLevel(), itemStackBlockPosPair.getSecond(), itemStackBlockPosPair.getFirst());
            }

            // Creeper Overhaul Part
            if (!type.replacer().isEmpty()) {
                Set<Map.Entry<Predicate<BlockState>, Function<RandomSource, BlockState>>> entries = type.replacer().entrySet();
                simulateExplosionForParameter.getToBlow().stream().map(BlockPos::below).forEach((pos) -> {
                    BlockState state = this.level.getBlockState(pos);
                    Iterator var4 = entries.iterator();

                    while(var4.hasNext()) {
                        Map.Entry<Predicate<BlockState>, Function<RandomSource, BlockState>> entry = (Map.Entry)var4.next();
                        if (((Predicate)entry.getKey()).test(state)) {
                            BlockState newState = (BlockState)((Function)entry.getValue()).apply(this.random);
                            if (newState != null) {
                                this.level.setBlock(pos, newState, 3);
                                break;
                            }
                        }
                    }

                });
            }
        }
    }

    private void spawnPotionCloud(){
        var self = ((BaseCreeper) (Object) this);
        Stream<MobEffectInstance> potions = Stream.concat(this.getActiveEffects().stream().map(MobEffectInstance::new), type.potionsWhenDead().stream().map(MobEffectInstance::new));
        ((SummonCloudWithEffectsMethodInvoker) self).invokeSummonCloudWithEffects(potions.toList());
    }
    
    private float getExplosionPower(){
        return 3.0F * (((BaseCreeper) (Object) this).isPowered() ? 2.0F : 1.0F);
    }
}
