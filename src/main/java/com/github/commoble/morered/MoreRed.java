package com.github.commoble.morered;

import com.github.commoble.morered.client.ClientEvents;
import com.github.commoble.morered.gatecrafting_plinth.GatecraftingRecipeButtonPacket;
import com.github.commoble.morered.plate_blocks.LogicGateType;
import com.github.commoble.morered.wire_post.IPostsInChunk;
import com.github.commoble.morered.wire_post.PostsInChunk;
import com.github.commoble.morered.wire_post.PostsInChunkCapability;
import com.github.commoble.morered.wire_post.WireBreakPacket;

import net.minecraft.util.ResourceLocation;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;

@Mod(MoreRed.MODID)
public class MoreRed
{
	public static final String MODID = "morered";
	
	// the network channel we'll use for sending packets associated with this mod
	public static final String CHANNEL_PROTOCOL_VERSION = "1";
	public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
		new ResourceLocation(MoreRed.MODID, "main"),
		() -> CHANNEL_PROTOCOL_VERSION,
		CHANNEL_PROTOCOL_VERSION::equals,
		CHANNEL_PROTOCOL_VERSION::equals);
	
	public static ResourceLocation getModRL(String name)
	{
		return new ResourceLocation(MODID, name);
	}
	
	public MoreRed()
	{
		IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
		IEventBus forgeBus = MinecraftForge.EVENT_BUS;
		
		ServerConfig.initServerConfig();
		
		MoreRed.addModListeners(modBus);
		MoreRed.addForgeListeners(forgeBus);
		
		// add layer of separation to client stuff so we don't break servers
		DistExecutor.runWhenOn(Dist.CLIENT, () -> () -> ClientEvents.addClientListeners(modBus, forgeBus));
		
		// blocks and blockitems for the logic gates are registered here
		LogicGateType.registerLogicGateTypes(BlockRegistrar.BLOCKS, ItemRegistrar.ITEMS);
	}
	
	public static void addModListeners(IEventBus modBus)
	{
		subscribedDeferredRegisters(modBus,
			BlockRegistrar.BLOCKS,
			ItemRegistrar.ITEMS,
			TileEntityRegistrar.TILES,
			ContainerRegistrar.CONTAINER_TYPES,
			RecipeRegistrar.RECIPE_SERIALIZERS);
		
		modBus.addListener(MoreRed::onCommonSetup);
	}
	
	public static void subscribedDeferredRegisters(IEventBus modBus, DeferredRegister<?>... registers)
	{
		for(DeferredRegister<?> register : registers)
		{
			register.register(modBus);
		}
	}
	
	public static void onCommonSetup(FMLCommonSetupEvent event)
	{
		// register packets
		int packetID = 0;
		MoreRed.CHANNEL.registerMessage(packetID++,
			GatecraftingRecipeButtonPacket.class,
			GatecraftingRecipeButtonPacket::write,
			GatecraftingRecipeButtonPacket::read,
			GatecraftingRecipeButtonPacket::handle);
		MoreRed.CHANNEL.registerMessage(packetID++,
			WireBreakPacket.class,
			WireBreakPacket::write,
			WireBreakPacket::read,
			WireBreakPacket::handle);
		
		// register capabilities
		CapabilityManager.INSTANCE.register(IPostsInChunk.class, new PostsInChunkCapability.Storage(), PostsInChunk::new);
	}
	
	public static void addForgeListeners(IEventBus forgeBus)
	{
		forgeBus.addGenericListener(Chunk.class, MoreRed::onAttachChunkCapabilities);
		forgeBus.addListener(EventPriority.LOWEST, MoreRed::onEntityPlaceBlock);
	}
	
	public static void onAttachChunkCapabilities(AttachCapabilitiesEvent<Chunk> event)
	{
		event.addCapability(getModRL(ObjectNames.POSTS_IN_CHUNK), new PostsInChunk());
	}
	
	public static void onEntityPlaceBlock(BlockEvent.EntityPlaceEvent event)
	{
		
	}
}
