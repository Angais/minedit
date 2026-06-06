package com.angel.aibuilder.client;

import com.angel.aibuilder.AiBuilderMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = AiBuilderMod.MODID, dist = Dist.CLIENT)
public class AiBuilderClient {
    public AiBuilderClient() {
        NeoForge.EVENT_BUS.register(new SelectionParticleRenderer());
    }
}
