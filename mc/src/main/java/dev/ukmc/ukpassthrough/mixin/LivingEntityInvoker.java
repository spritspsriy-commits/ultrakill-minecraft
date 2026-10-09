package dev.ukmc.ukpassthrough.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Minecraft's own steps for the player's damage and death, for V1's (PlayerMixin, the totem): armor (which wears it, as
 * any hit does), Resistance and protection enchantments, and the totem of undying.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityInvoker {
	@Invoker("getDamageAfterArmorAbsorb")
	float ukpassthrough$afterArmor(DamageSource source, float amount);

	@Invoker("getDamageAfterMagicAbsorb")
	float ukpassthrough$afterMagic(DamageSource source, float amount);

	@Invoker("checkTotemDeathProtection")
	boolean ukpassthrough$useTotem(DamageSource source);

	/** What a death drops (for a player: the inventory, unless the keepInventory rule, curse of vanishing items gone first). */
	@Invoker("dropEquipment")
	void ukpassthrough$dropEquipment(ServerLevel level);
}
