package com.einsteinmods.schematicvault.client.cache;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * A thumbnail on the GPU, sampled linearly: list rows draw the 96 px picture at about a fifth of
 * its size, where vanilla's nearest sampling ({@code DynamicTexture}) would drop most pixels.
 */
final class ThumbnailTexture extends DynamicTexture {
  ThumbnailTexture(String label, NativeImage image) {
    super(() -> label, image);
    this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
  }
}
