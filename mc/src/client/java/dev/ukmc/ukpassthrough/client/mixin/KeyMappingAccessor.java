package dev.ukmc.ukpassthrough.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The key a mapping is bound to now: V1's punch key holds Minecraft's attack or use key down (V1Hand) wherever the player bound it. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("key")
	InputConstants.Key ukpassthrough$key();
}
