package com.structureharmony;

import com.mojang.logging.LogUtils;
import com.structureharmony.command.SHCommand;
import com.structureharmony.spacing.SpacingEngine;
import com.structureharmony.spacing.StructureSizes;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(StructureHarmony.MODID)
public final class StructureHarmony {
    public static final String MODID = "structureharmony";
    public static final Logger LOG = LogUtils.getLogger();

    public StructureHarmony() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SHConfig.SPEC, MODID + "-common.toml");
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onConfigChanged);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onConfigChanged(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == SHConfig.SPEC) {
            SHRules.invalidate();
            SpacingEngine.invalidate();
            LOG.info("[StructureHarmony] config (re)loaded, rules invalidated");
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SHCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        SHStats.reset();
        StructureSizes.reset();
    }
}
