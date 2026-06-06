package com.angel.aibuilder;

import com.angel.aibuilder.build.BuildQueue;
import com.angel.aibuilder.commands.AiBuilderCommands;
import com.angel.aibuilder.selection.SelectionEvents;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(AiBuilderMod.MODID)
public class AiBuilderMod {
    public static final String MODID = "minedit";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AiBuilderMod(IEventBus modEventBus) {
        LOGGER.info("Minedit loaded. Use /apikey, /model, /build, and /edit.");
        NeoForge.EVENT_BUS.register(new AiBuilderCommands());
        NeoForge.EVENT_BUS.register(new PlayerWarningEvents());
        NeoForge.EVENT_BUS.register(new SelectionEvents());
        NeoForge.EVENT_BUS.register(new BuildQueue());
    }
}
