package com.example.bloodarts;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sneak + right click : cycle technique
 * Right click         : use selected technique
 * Every technique costs some of your own health and has its own cooldown.
 */
public class BloodFocusItem extends Item {

    private enum Ability {
        PIERCING("Piercing Shot", 2f, 12),   // 1 heart,  0.6s
        SLASH("Blood Slash",      3f, 30),   // 1.5 hearts, 1.5s
        BIND("Crimson Bind",      2f, 60),   // 1 heart,  3s
        DRAIN("Blood Drain",      1f, 60),   // 0.5 heart, 3s (net heal)
        DASH("Crimson Dash",      2f, 50),   // 1 heart,  2.5s
        ARMOR("Blood Armor",      2f, 200),  // 1 heart,  10s
        BOOST("Blood Boost",      4f, 100),  // 2 hearts, 5s
        NOVA("Blood Nova",        6f, 300);  // 3 hearts, 15s

        final String label; final float cost; final int cooldown;
        Ability(String label, float cost, int cooldown) {
            this.label = label; this.cost = cost; this.cooldown = cooldown;
        }
    }

    private record Ray(Vec3d end, LivingEntity target) {}

    private static final Map<UUID, long[]> COOLDOWNS = new HashMap<>();
    private static final DustParticleEffect BLOOD =
            new DustParticleEffect(new Vector3f(0.65f, 0.0f, 0.02f), 1.3f);

    public BloodFocusItem(Settings settings) { super(settings); }

    // ---------- selection stored on the item ----------
    private static int getSelected(ItemStack s) {
        int i = s.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt().getInt("ability");
        return Math.floorMod(i, Ability.values().length);
    }
    private static void setSelected(ItemStack s, int i) {
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, s, nbt -> nbt.putInt("ability", i));
    }

    @Override
    public void appendTooltip(ItemStack stack, Item.TooltipContext ctx, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.literal("Technique: " + Ability.values()[getSelected(stack)].label).formatted(Formatting.RED));
        tooltip.add(Text.literal("Sneak + right click to switch").formatted(Formatting.GRAY));
    }

    // ---------- main use ----------
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (world.isClient) return TypedActionResult.success(stack);
        ServerWorld sw = (ServerWorld) world;
        Ability[] all = Ability.values();
        int sel = getSelected(stack);

        if (player.isSneaking()) {
            sel = (sel + 1) % all.length;
            setSelected(stack, sel);
            msg(player, "Technique: " + all[sel].label, Formatting.DARK_RED);
            sw.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.6f, 0.7f);
            return TypedActionResult.success(stack);
        }

        Ability a = all[sel];
        long now = sw.getTime();
        long[] ready = COOLDOWNS.computeIfAbsent(player.getUuid(), k -> new long[all.length]);
        if (ready[sel] > now) {
            msg(player, a.label + " recharging (" + String.format("%.1f", (ready[sel] - now) / 20.0) + "s)", Formatting.GRAY);
            return TypedActionResult.fail(stack);
        }
        if (!player.isCreative() && player.getHealth() <= a.cost) {
            msg(player, "Not enough blood...", Formatting.RED);
            return TypedActionResult.fail(stack);
        }

        boolean ok = switch (a) {
            case PIERCING -> piercing(sw, player);
            case SLASH -> slash(sw, player);
            case BIND -> bind(sw, player);
            case DRAIN -> drain(sw, player);
            case DASH -> dash(sw, player);
            case ARMOR -> armor(sw, player);
            case BOOST -> boost(sw, player);
            case NOVA -> nova(sw, player);
        };
        if (!ok) {
            msg(player, "No target in range", Formatting.GRAY);
            return TypedActionResult.fail(stack);
        }

        if (!player.isCreative()) player.setHealth(player.getHealth() - a.cost);
        ready[sel] = now + a.cooldown;
        return TypedActionResult.success(stack);
    }

    // ---------- techniques ----------
    private boolean piercing(ServerWorld sw, PlayerEntity p) {
        Vec3d dir = p.getRotationVec(1f);
        Ray r = cast(sw, p, 32);
        if (r.target != null) {
            r.target.damage(sw.getDamageSources().playerAttack(p), 10f);
            r.target.takeKnockback(0.6, -dir.x, -dir.z);
        }
        beam(sw, p.getEyePos(), r.end);
        sound(sw, p, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f);
        return true;
    }

    private boolean slash(ServerWorld sw, PlayerEntity p) {
        Vec3d look = p.getRotationVec(1f);
        Vec3d eye = p.getEyePos();
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(6), x -> x != p && x.isAlive())) {
            Vec3d to = e.getEyePos().subtract(eye);
            if (to.length() <= 5.5 && to.normalize().dot(look) > 0.5) {
                e.damage(sw.getDamageSources().playerAttack(p), 8f);
                e.takeKnockback(0.5, -look.x, -look.z);
            }
        }
        for (int a = -60; a <= 60; a += 8) {
            double rad = Math.toRadians(p.getYaw() + a);
            Vec3d d = new Vec3d(-Math.sin(rad), 0, Math.cos(rad));
            for (double r = 1; r <= 5; r += 0.5) {
                Vec3d pt = eye.add(d.multiply(r));
                sw.spawnParticles(BLOOD, pt.x, pt.y - 0.5, pt.z, 1, 0, 0, 0, 0);
            }
        }
        Vec3d mid = eye.add(look.multiply(2.5));
        sw.spawnParticles(ParticleTypes.SWEEP_ATTACK, mid.x, mid.y - 0.3, mid.z, 1, 0, 0, 0, 0);
        sound(sw, p, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.9f);
        return true;
    }

    private boolean bind(ServerWorld sw, PlayerEntity p) {
        Ray r = cast(sw, p, 20);
        if (r.target == null) return false;
        r.target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 4));
        r.target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100, 1));
        r.target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 100, 0));
        beam(sw, p.getEyePos(), r.end);
        sw.spawnParticles(BLOOD, r.target.getX(), r.target.getBodyY(0.5), r.target.getZ(), 40, 0.4, 0.8, 0.4, 0.02);
        sound(sw, p, SoundEvents.ENTITY_EVOKER_CAST_SPELL, 0.7f);
        return true;
    }

    private boolean drain(ServerWorld sw, PlayerEntity p) {
        Ray r = cast(sw, p, 8);
        if (r.target == null) return false;
        r.target.damage(sw.getDamageSources().playerAttack(p), 6f);
        p.heal(4f);
        beam(sw, r.target.getEyePos(), p.getEyePos());   // blood flows back to you
        sw.spawnParticles(ParticleTypes.HEART, p.getX(), p.getBodyY(1.0), p.getZ(), 4, 0.3, 0.3, 0.3, 0);
        sound(sw, p, SoundEvents.ENTITY_GENERIC_DRINK, 0.7f);
        return true;
    }

    private boolean dash(ServerWorld sw, PlayerEntity p) {
        Vec3d look = p.getRotationVec(1f);
        Box path = p.getBoundingBox().stretch(look.multiply(4)).expand(1);
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, path, x -> x != p && x.isAlive())) {
            e.damage(sw.getDamageSources().playerAttack(p), 6f);
            e.takeKnockback(0.8, -look.x, -look.z);
        }
        for (double d = 0; d < 5; d += 0.5) {
            Vec3d pt = p.getPos().add(look.multiply(d));
            sw.spawnParticles(BLOOD, pt.x, pt.y + 1, pt.z, 3, 0.2, 0.4, 0.2, 0.01);
        }
        p.setVelocity(look.x * 2.0, Math.max(look.y * 1.2, 0.2), look.z * 2.0);
        p.velocityModified = true;
        p.fallDistance = 0;
        sound(sw, p, SoundEvents.ENTITY_PHANTOM_FLAP, 0.8f);
        return true;
    }

    private boolean armor(ServerWorld sw, PlayerEntity p) {
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 300, 1));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 300, 0));
        for (int a = 0; a < 360; a += 20) {
            double rad = Math.toRadians(a);
            for (double y = 0; y <= 2; y += 0.5)
                sw.spawnParticles(BLOOD, p.getX() + Math.cos(rad), p.getY() + y, p.getZ() + Math.sin(rad), 1, 0, 0, 0, 0);
        }
        sound(sw, p, SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), 0.6f);
        return true;
    }

    private boolean boost(ServerWorld sw, PlayerEntity p) {
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 200, 1));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 200, 1));
        sw.spawnParticles(BLOOD, p.getX(), p.getBodyY(0.5), p.getZ(), 40, 0.5, 0.8, 0.5, 0.05);
        sound(sw, p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 0.8f);
        return true;
    }

    private boolean nova(ServerWorld sw, PlayerEntity p) {
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(6), x -> x != p && x.isAlive())) {
            if (e.distanceTo(p) <= 6) {
                e.damage(sw.getDamageSources().playerAttack(p), 12f);
                e.takeKnockback(1.2, p.getX() - e.getX(), p.getZ() - e.getZ());
            }
        }
        for (int a = 0; a < 360; a += 10) {
            double rad = Math.toRadians(a);
            for (double r = 1; r <= 6; r += 1)
                sw.spawnParticles(BLOOD, p.getX() + Math.cos(rad) * r, p.getY() + 0.3 + r * 0.1, p.getZ() + Math.sin(rad) * r, 1, 0, 0.1, 0, 0.02);
        }
        sw.spawnParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY() + 1, p.getZ(), 1, 0, 0, 0, 0);
        sound(sw, p, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 0.8f);
        return true;
    }

    // ---------- helpers ----------
    private Ray cast(ServerWorld sw, PlayerEntity p, double range) {
        Vec3d start = p.getEyePos();
        Vec3d dir = p.getRotationVec(1f);
        Vec3d end = start.add(dir.multiply(range));
        var blockHit = sw.raycast(new RaycastContext(start, end,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
        if (blockHit.getType() != HitResult.Type.MISS) end = blockHit.getPos();
        Box box = p.getBoundingBox().stretch(dir.multiply(range)).expand(1.0);
        EntityHitResult hit = ProjectileUtil.raycast(p, start, end, box,
                e -> !e.isSpectator() && e.canHit(), start.squaredDistanceTo(end));
        if (hit != null) {
            Entity t = hit.getEntity();
            return new Ray(hit.getPos(), t instanceof LivingEntity l ? l : null);
        }
        return new Ray(end, null);
    }

    private void beam(ServerWorld sw, Vec3d from, Vec3d to) {
        Vec3d dir = to.subtract(from);
        double len = dir.length();
        if (len < 0.01) return;
        dir = dir.normalize();
        for (double d = 1.0; d < len; d += 0.4) {
            Vec3d pt = from.add(dir.multiply(d));
            sw.spawnParticles(BLOOD, pt.x, pt.y - 0.1, pt.z, 2, 0.02, 0.02, 0.02, 0);
        }
        sw.spawnParticles(BLOOD, to.x, to.y, to.z, 20, 0.2, 0.2, 0.2, 0.1);
    }

    private void sound(ServerWorld sw, PlayerEntity p, net.minecraft.sound.SoundEvent s, float pitch) {
        sw.playSound(null, p.getBlockPos(), s, SoundCategory.PLAYERS, 1.2f, pitch);
    }

    private void msg(PlayerEntity p, String text, Formatting f) {
        p.sendMessage(Text.literal(text).formatted(f), true);
    }
}
