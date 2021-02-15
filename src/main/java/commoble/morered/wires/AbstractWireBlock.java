package commoble.morered.wires;

import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import commoble.morered.api.MoreRedAPI;
import commoble.morered.api.WireConnector;
import commoble.morered.util.DirectionHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.SixWayBlock;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.BooleanProperty;
import net.minecraft.state.StateContainer.Builder;
import net.minecraft.util.Direction;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.IWorld;
import net.minecraft.world.IWorldReader;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.util.Constants.BlockFlags;

public abstract class AbstractWireBlock extends Block
{
	
	public static final BooleanProperty DOWN = SixWayBlock.DOWN;
	public static final BooleanProperty UP = SixWayBlock.UP;
	public static final BooleanProperty NORTH = SixWayBlock.NORTH;
	public static final BooleanProperty SOUTH = SixWayBlock.SOUTH;
	public static final BooleanProperty WEST = SixWayBlock.WEST;
	public static final BooleanProperty EAST = SixWayBlock.EAST;
	public static final BooleanProperty[] INTERIOR_FACES = {DOWN,UP,NORTH,SOUTH,WEST,EAST};
	
	/**
	 * 
	 * @param nodeShapes array of 6 voxelshapes by face direction
	 * @param lineShapes array of 24 voxelshapes by face direction + secondary direction
	 * @return
	 */
	public static VoxelShape[] makeVoxelShapes(VoxelShape[] nodeShapes, VoxelShape[] lineShapes)
	{
		VoxelShape[] result = new VoxelShape[64];
		
		for (int i=0; i<64; i++)
		{
			VoxelShape nextShape = VoxelShapes.empty();
			boolean[] addedSides = new boolean[6]; // sides for which we've already added node shapes to
			for (int side=0; side<6; side++)
			{
				if ((i & (1 << side)) != 0)
				{
					nextShape = VoxelShapes.or(nextShape, nodeShapes[side]);
					
					int sideAxis = side/2; // 0,1,2 = y,z,x
					for (int secondarySide = 0; secondarySide < side; secondarySide++)
					{
						if (addedSides[secondarySide] && sideAxis != secondarySide/2) // the two sides are orthagonal to each other (not parallel)
						{
							// add line shapes for the elbows
							nextShape = VoxelShapes.or(nextShape, getLineShape(lineShapes, side, DirectionHelper.getCompressedSecondSide(side,secondarySide)));
							nextShape = VoxelShapes.or(nextShape, getLineShape(lineShapes, secondarySide, DirectionHelper.getCompressedSecondSide(secondarySide,side)));
						}
					}
					
					addedSides[side] = true;
				}
			}
			result[i] = nextShape;
		}
		
		return result;
	}
	
	public static VoxelShape getLineShape(VoxelShape[] lineShapes, int side, int secondarySide)
	{
		return lineShapes[side*4 + secondarySide];
	}
	
	/**
	 * Get the index of the primary shape for the given wireblock blockstate (64 combinations)
	 * @param state A blockstate belonging to an AbstractWireBlock
	 * @return An index in the range [0, 63]
	 */
	public static int getShapeIndex(BlockState state)
	{
		int index = 0;
		int sideCount = INTERIOR_FACES.length;
		for (int side=0; side < sideCount; side++)
		{
			if (state.get(INTERIOR_FACES[side]))
			{
				index |= 1 << side;
			}
		}
		
		return index;
	}
	
	public static VoxelShape makeExpandedShapeForIndex(VoxelShape[] shapesByStateIndex, VoxelShape[] lineShapes, long index)
	{
		int primaryShapeIndex = (int) (index & 63);
		long expandedShapeIndex = index >> 6;
		VoxelShape shape = shapesByStateIndex[primaryShapeIndex];
		// we want to use the index to combine secondary line shapes with the actual voxelshape for a given state
		int flag = 1;
		for (int side = 0; side < 6; side++)
		{
			for (int subSide = 0; subSide < 4; subSide++)
			{
				if ((expandedShapeIndex & flag) != 0)
				{
					shape = VoxelShapes.or(shape, AbstractWireBlock.getLineShape(lineShapes, side, subSide));
				}
				flag = flag << 1;
			}
		}
		return shape;
	}
	
	public static LoadingCache<Long, VoxelShape> makeVoxelCache(VoxelShape[] shapesByStateIndex, VoxelShape[] lineShapes)
	{
		return CacheBuilder.newBuilder()
			.expireAfterAccess(5, TimeUnit.MINUTES)
			.build(new CacheLoader<Long, VoxelShape>()
			{
	
				@Override
				public VoxelShape load(Long key) throws Exception
				{
					return AbstractWireBlock.makeExpandedShapeForIndex(shapesByStateIndex, lineShapes, key);
				}
			
			});
	}

	protected final VoxelShape[] shapesByStateIndex;
	protected final VoxelShape[] raytraceBackboards;
	protected final LoadingCache<Long, VoxelShape> voxelCache;
	protected final boolean useIndirectPower;

	/**
	 * 
	 * @param properties block properties
	 * @param shapesByStateIndex Array of 64 voxelshapes, the voxels for the canonical blockstates
	 * @param raytraceBackboards Array of 6 voxelshapes (by attachment face direction) used for subwire raytrace checking
	 */
	public AbstractWireBlock(Properties properties, VoxelShape[] shapesByStateIndex, VoxelShape[] raytraceBackboards, LoadingCache<Long, VoxelShape> voxelCache, boolean useIndirectPower)
	{
		super(properties);
		// the "default" state has to be the empty state so we can build it up one face at a time
		this.setDefaultState(this.getStateContainer().getBaseState()
			.with(DOWN, false)
			.with(UP, false)
			.with(NORTH, false)
			.with(SOUTH, false)
			.with(WEST, false)
			.with(EAST, false)
			);
		this.shapesByStateIndex = shapesByStateIndex;
		this.raytraceBackboards = raytraceBackboards;
		this.voxelCache = voxelCache;
		this.useIndirectPower = useIndirectPower;
	}

	protected abstract void updatePower(World world, BlockPos wirePos, BlockState wireState);
	protected abstract int getPower(BlockState state, IBlockReader world, BlockPos pos, Direction directionFromNeighbor, boolean expand);
	protected abstract void notifyNeighbors(World world, BlockPos wirePos, BlockState newState, EnumSet<Direction> updateDirections, boolean doConductedPowerUpdates);

	@Override
	protected void fillStateContainer(Builder<Block, BlockState> builder)
	{
		super.fillStateContainer(builder);
		builder.add(DOWN,UP,NORTH,SOUTH,WEST,EAST);
	}

	@Override
	public VoxelShape getShape(BlockState state, IBlockReader worldIn, BlockPos pos, ISelectionContext context)
	{
		return worldIn instanceof World
			? VoxelCache.get((World)worldIn).getWireShape(pos)
			: this.shapesByStateIndex[getShapeIndex(state)];
	}

	// overriding this so we don't delegate to getShape for the render shape (to reduce lookups of the extended shape)
	@Override
	public VoxelShape getRenderShape(BlockState state, IBlockReader worldIn, BlockPos pos)
	{
		return this.shapesByStateIndex[getShapeIndex(state)];
	}

	@Override
	public boolean isReplaceable(BlockState state, BlockItemUseContext useContext)
	{
		return this.isEmptyWireBlock(state);
	}

	@Override
	public BlockState updatePostPlacement(BlockState thisState, Direction directionToNeighbor, BlockState neighborState, IWorld world, BlockPos thisPos, BlockPos neighborPos)
	{
		BooleanProperty sideProperty = INTERIOR_FACES[directionToNeighbor.ordinal()];
		if (thisState.get(sideProperty)) // if wire is attached on the relevant face, check if it should be disattached
		{
			Direction neighborSide = directionToNeighbor.getOpposite();
			boolean isNeighborSideSolid = neighborState.isSolidSide(world, neighborPos, neighborSide);
			if (!isNeighborSideSolid && world instanceof ServerWorld)
			{
				Block.spawnDrops(this.getDefaultState().with(sideProperty, true), (ServerWorld)world, thisPos);
			}
			return thisState.with(sideProperty, isNeighborSideSolid);
		}
		else
		{
			return thisState;
		}
	}

	@Override
	public boolean isValidPosition(BlockState state, IWorldReader world, BlockPos pos)
	{
		Direction[] dirs = Direction.values();
		for (int side=0; side<6; side++)
		{
			if (state.get(INTERIOR_FACES[side]))
			{
				Direction dir = dirs[side];
				BlockPos neighborPos = pos.offset(dir);
				BlockState neighborState = world.getBlockState(neighborPos);
				Direction neighborSide = dir.getOpposite();
				if (!neighborState.isSolidSide(world, neighborPos, neighborSide))
				{
					return false;
				}
			}
		}
		return true;
	}

	// we can use the blockitem onItemUse to handle adding extra nodes to an existing wire
	// so for this, we can assume that we're placing a fresh wire
	/**
	 * @return null if no state should be placed, or the state that should be placed otherwise
	 */
	@Override
	@Nullable
	public BlockState getStateForPlacement(BlockItemUseContext context)
	{
		World world = context.getWorld();
		Direction sideOfNeighbor = context.getFace();
		Direction directionToNeighbor = sideOfNeighbor.getOpposite();
		// with standard BlockItemUseContext rules,
		// if replacing a block, this is the position of the block we're replacing
		// otherwise, it's the position adjacent to the clicked block (offset by sideOfNeighbor)
		BlockPos placePos = context.getPos();
		BlockPos neighborPos = placePos.offset(directionToNeighbor);
		BlockState neighborState = world.getBlockState(neighborPos);
		return neighborState.isSolidSide(world, neighborPos, sideOfNeighbor)
			? this.getDefaultState().with(INTERIOR_FACES[directionToNeighbor.ordinal()], true)
			: null;
	}

	
	/**
	 * Spawn a digging particle effect in the world, this is a wrapper around
	 * EffectRenderer.addBlockHitEffects to allow the block more control over the
	 * particles. Useful when you have entirely different texture sheets for
	 * different sides/locations in the world.
	 *
	 * @param state
	 *            The current state
	 * @param world
	 *            The current world
	 * @param target
	 *            The target the player is looking at {x/y/z/side/sub}
	 * @param manager
	 *            A reference to the current particle manager.
	 * @return True to prevent vanilla digging particles form spawning.
	 */
	@Override
	@OnlyIn(Dist.CLIENT)
	public boolean addHitEffects(BlockState state, World worldObj, RayTraceResult target, ParticleManager manager)
	{
		return this.getWireCount(state) == 0; // if we have no wires here, we have no shape, so return true to disable particles
		// (otherwise the particle manager crashes because it performs an unsupported operation on the empty shape)
	}
	
	// called when a different block instance is replaced with this one
	// only called on server
	// called after previous block and TE are removed, but before this block's TE is added
	@Override
	public void onBlockAdded(BlockState state, World worldIn, BlockPos pos, BlockState oldState, boolean isMoving)
	{
		this.updateShapeCache(worldIn, pos);
		super.onBlockAdded(state, worldIn, pos, oldState, isMoving);
	}

	// called when a player places this block or adds a wire to a wire block
	// also called on the client of the placing player
	@Override
	public void onBlockPlacedBy(World worldIn, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack)
	{
		if (!worldIn.isRemote)
		{
			for (Direction directionToNeighbor : Direction.values())
			{
				BlockPos neighborPos = pos.offset(directionToNeighbor);
				BlockState neighborState = worldIn.getBlockState(neighborPos);
				if (neighborState.isAir())
				{
					this.addEmptyWireToAir(state, worldIn, neighborPos, directionToNeighbor);
				}
			}
		}
		this.updateShapeCache(worldIn, pos);
		this.updatePower(worldIn, pos, state);
		super.onBlockPlacedBy(worldIn, pos, state, placer, stack);
	}

	// called when the blockstate at the given pos changes
	// only called on servers
	// oldState is a state of this block, newState may or may not be
	@Override
	public void onReplaced(BlockState oldState, World worldIn, BlockPos pos, BlockState newState, boolean isMoving)
	{
		// if this is an empty wire block, remove it if no edges are valid anymore
		boolean doPowerUpdate = true;
		if (newState.getBlock() != this) // wire block was completely destroyed
		{
			this.notifyNeighbors(worldIn, pos, newState, EnumSet.allOf(Direction.class), this.useIndirectPower);
			doPowerUpdate = false;
		}
		else if (this.isEmptyWireBlock(newState)) // wire block has no attached faces and consists only of fake wire edges
		{
			long edgeFlags = this.getEdgeFlags(worldIn,pos);
			if (edgeFlags == 0)	// if we don't need to be rendering any edges, set wire block to air
			{
				// removing the block will call onReplaced again, but using the newBlock != this condition
				worldIn.removeBlock(pos, false);
			}
			else
			{
				// so only notify neighbors if we *don't* completely remove the block
				this.notifyNeighbors(worldIn, pos, newState, EnumSet.allOf(Direction.class), this.useIndirectPower);
			}
			doPowerUpdate = false;
		}
		this.updateShapeCache(worldIn, pos);
		super.onReplaced(oldState, worldIn, pos, newState, isMoving);
		// if the new state is still a wire block and has at least one wire in it, do a power update
		if (doPowerUpdate)
			this.updatePower(worldIn, pos, newState);
	}

	// called when a neighboring blockstate changes, not called on the client
	@Override
	public void neighborChanged(BlockState state, World worldIn, BlockPos pos, Block blockIn, BlockPos fromPos, boolean isMoving)
	{
		// if this is an empty wire block, remove it if no edges are valid anymore
		if (this.isEmptyWireBlock(state))
		{
			long edgeFlags = this.getEdgeFlags(worldIn,pos);
			if (edgeFlags == 0)
			{
				worldIn.removeBlock(pos, false);
			}
		}
		// if this is a non-empty wire block and the changed state is an air block
		else if (worldIn.isAirBlock(fromPos))
		{
			BlockPos offset = fromPos.subtract(pos);
			Direction directionToNeighbor = Direction.byLong(offset.getX(), offset.getY(), offset.getZ());
			if (directionToNeighbor != null)
			{
				this.addEmptyWireToAir(state, worldIn, fromPos, directionToNeighbor);
			}
		}

		this.updateShapeCache(worldIn, pos);
		this.updatePower(worldIn, pos, state);
		super.neighborChanged(state, worldIn, pos, blockIn, fromPos, isMoving);
	}

	@Override
	public BlockState rotate(BlockState state, Rotation rot)
	{
		BlockState result = state;
		for (int i=0; i<4; i++) // rotations only rotated about the y-axis, we only need to rotated the horizontal faces
		{
			Direction dir = Direction.byHorizontalIndex(i);
			Direction newDir = rot.rotate(dir);
			result = result.with(INTERIOR_FACES[newDir.ordinal()], state.get(INTERIOR_FACES[dir.ordinal()]));
		}
		return result;
	}

	@Override
	public BlockState mirror(BlockState state, Mirror mirrorIn)
	{
		BlockState result = state;
		for (int i=0; i<4; i++) // only horizontal sides get mirrored
		{
			Direction dir = Direction.byHorizontalIndex(i);
			Direction newDir = mirrorIn.mirror(dir);
			result = result.with(INTERIOR_FACES[newDir.ordinal()], state.get(INTERIOR_FACES[dir.ordinal()]));
		}
		return result;
	}

	public int getWireCount(BlockState state)
	{
		int count = 0;
		for (int side=0; side<6; side++)
		{
			if (state.get(INTERIOR_FACES[side]))
			{
				count++;
			}
		}
		return count;
	}
	
	public boolean isEmptyWireBlock(BlockState state)
	{
		return state == this.getDefaultState();
	}
	
	protected long getEdgeFlags(IBlockReader world, BlockPos pos)
	{
		long result = 0;
		for (int edge=0; edge<12; edge++)
		{
			if (Edge.values()[edge].shouldEdgeRender(world, pos, this))
			{
				result |= (1L << (30 + edge));
			}
		}
		return result;
	}
	
	@Nullable
	public Direction getInteriorFaceToBreak(BlockState state, BlockPos pos, PlayerEntity player, BlockRayTraceResult raytrace, float partialTicks)
	{
		// raytrace against the face voxels, get the one the player is actually pointing at
		// this is invoked when the player has been holding left-click sufficiently long enough for the block to break
			// (so it this is an input response, not a performance-critical method)
		// so we can assume that the player is still looking directly at the block's interaction shape
		// do one raytrace against the state's interaction voxel itself,
		// then compare the hit vector to the six face cuboids
		// and see if any of them contain the hit vector
		// we'll need the player to not be null though
		Vector3d lookOffset = player.getLook(partialTicks); // unit normal vector in look direction
		Vector3d hitVec = raytrace.getHitVec();
		Vector3d relativeHitVec = hitVec
			.add(lookOffset.mul(0.001D, 0.001D, 0.001D))
			.subtract(pos.getX(), pos.getY(), pos.getZ()); // we're also wanting this to be relative to the voxel
		for (int side=0; side<6; side++)
		{
			if (state.get(INTERIOR_FACES[side]))
			{
				// figure out which part of the shape we clicked
				VoxelShape faceShape = this.raytraceBackboards[side];
				
				for (AxisAlignedBB aabb : faceShape.toBoundingBoxList())
				{
					if (aabb.contains(relativeHitVec))
					{
						return Direction.byIndex(side);
					}
				}
			}
		}
		
		// if we failed to removed any particular state, return false so the whole block doesn't get broken
		return null;
	}
	
	protected void updateShapeCache(World world, BlockPos pos)
	{
		LoadingCache<BlockPos, VoxelShape> cache = VoxelCache.get(world).shapesByPos;
		cache.invalidate(pos);
		for (int i=0; i<6; i++)
		{
			cache.invalidate(pos.offset(Direction.byIndex(i)));
		}
		if (world instanceof ServerWorld)
		{
			WireUpdateBuffer.get((ServerWorld)world).enqueue(pos);
		}
	}

	protected void addEmptyWireToAir(BlockState thisState, World world, BlockPos neighborAirPos, Direction directionToNeighbor)
	{
		Direction directionFromNeighbor = directionToNeighbor.getOpposite();
		for (Direction dir : Direction.values())
		{
			if (dir != directionToNeighbor && dir != directionFromNeighbor)
			{
				BooleanProperty thisAttachmentFace = INTERIOR_FACES[dir.ordinal()];
				if (thisState.get(thisAttachmentFace))
				{
					BlockPos diagonalNeighbor = neighborAirPos.offset(dir);
					BooleanProperty neighborAttachmentFace = INTERIOR_FACES[directionFromNeighbor.ordinal()];
					BlockState diagonalState = world.getBlockState(diagonalNeighbor);
					if (diagonalState.getBlock() == this.getBlock() && diagonalState.get(neighborAttachmentFace))
					{
						world.setBlockState(neighborAirPos, this.getDefaultState());
						break; // we don't need to set the wire block more than once
					}
				}
			}
		}
	}
	
	public void destroyClickedSegment(BlockState state, World world, BlockPos pos, PlayerEntity player, Direction interiorFace, boolean dropItems)
	{
		int side = interiorFace.ordinal();
		BooleanProperty sideProperty = INTERIOR_FACES[side];
		if (state.get(sideProperty))
		{
			BlockState newState = state.with(sideProperty, false);
			Block newBlock = newState.getBlock();
			BlockState removedState = newBlock.getDefaultState().with(sideProperty, true);
			Block removedBlock = removedState.getBlock();
			if (dropItems)
			{
				// add player stats and spawn drops
				removedBlock.harvestBlock(world, player, pos, removedState, null, player.getHeldItemMainhand().copy());
			}
			if (world.isRemote)
			{
				// on the server world, onBlockHarvested will play the break effects for each player except the player given
				// and also anger piglins at that player, so we do want to give it the player
				// on the client world, willHarvest is always false, so we need to manually play the effects for that player
				world.playEvent(player, 2001, pos, Block.getStateId(removedState));
			}
			else
			{
				// plays the break event for every nearby player except the breaking player
				removedBlock.onBlockHarvested(world, pos, removedState, player);
			}
			// default and rerender flags are used when block is broken on client
			if (world.setBlockState(pos, newState, BlockFlags.DEFAULT_AND_RERENDER))
			{
				removedBlock.onPlayerDestroy(world, pos, removedState);
			}
		}
		
		this.updateShapeCache(world, pos);
	}
	
	/**
	 * Get the index of the expanded shape for the given wireblock blockstate (including non-blockstate-based shapes);
	 * this is a bit pattern, the bits are defined as such starting from the least-significant bit:
	 * bits 0-5 -- the unexpanded shape index for a given state (indicating which of the six interior faces wires are attached to)
	 * bits 6-29 (the next 24 bits) -- the flags indicating which adjacent neighbors the faces are connecting to
	 * 	each primary face can connect to the faces of four neighbors (6*4 = 24)
	 * bits 30-41 (the next 12 bits) -- the flags indicating any edges connecting wires diagonally
	 * @param state A blockstate belonging to a WireBlock
	 * @param world world
	 * @param pos position of the blockstate
	 * @return An index usable by the voxel cache
	 */
	public long getExpandedShapeIndex(BlockState state, IBlockReader world, BlockPos pos)
	{
		// for each of the six interior faces a wire block can have a wire attached to,
		// that face can be connected to any of the four orthagonally adjacent blocks
		// producing an additional component of the resulting shape
		// 6*4 = 24, we have 24 boolean properties, so we have 2^24 different possible shapes
		// we can store these in a bit pattern for efficient keying
		long result = 0;
		Map<Block, WireConnector> wireConnectors = MoreRedAPI.getWireConnectabilityRegistry();
		WireConnector defaultConnector = MoreRedAPI.getDefaultWireConnector();
		for (int side = 0; side < 6; side++)
		{
			// we have to have a wire attached to a side to connect to secondary sides
			BooleanProperty attachmentSide = INTERIOR_FACES[side];
			if (state.get(attachmentSide))
			{
				result |= (1L << side);
				Direction attachmentDirection = Direction.byIndex(side);
				for (int subSide = 0; subSide < 4; subSide++)
				{
					int secondaryOrdinal = DirectionHelper.uncompressSecondSide(side, subSide);
					if (state.get(INTERIOR_FACES[secondaryOrdinal]))
						continue; // dont add lines if the state already has an elbow here
					Direction secondaryDir = Direction.byIndex(secondaryOrdinal);
					Direction directionToWire = secondaryDir.getOpposite();
					BlockPos neighborPos = pos.offset(secondaryDir);
					BlockState neighborState = world.getBlockState(neighborPos);
					Block neighborBlock = neighborState.getBlock();
					// first, check if the neighbor state is a wire that shares this attachment side
					if (wireConnectors.getOrDefault(neighborBlock, defaultConnector)
						.canConnectToAdjacentWire(world, pos, state, attachmentDirection, directionToWire, neighborPos, neighborState))
					{
						result |= (1L << (side*4 + subSide + 6));
					}
				}
			}
		}
		result = result | this.getEdgeFlags(world,pos);
		return result;
	}
	
	public VoxelShape getCachedExpandedShapeVoxel(BlockState wireState, World world, BlockPos pos)
	{
		long index = this.getExpandedShapeIndex(wireState, world, pos);
		
		return this.voxelCache.getUnchecked(index);
	}
}
