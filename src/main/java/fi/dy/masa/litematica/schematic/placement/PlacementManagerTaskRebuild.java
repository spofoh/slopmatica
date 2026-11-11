package fi.dy.masa.litematica.schematic.placement;

import java.util.Collection;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.util.WorldPlacingUtils;
import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.ChunkSchematicState;
import fi.dy.masa.litematica.world.ProtoChunkSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;

public class PlacementManagerTaskRebuild extends PlacementManagerTask
{
	private final Runnable task;

	protected PlacementManagerTaskRebuild(Supplier<WorldSchematic> worldSupplier, int chunkX, int chunkZ)
	{
		super(worldSupplier, chunkX, chunkZ);
		this.task = this.buildTask();
	}

	@Override
	public void run()
	{
		this.task.run();
	}

	@Override
	protected Runnable buildTask()
	{
		return () ->
		{
			SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
			WorldSchematic worldSchematic = this.worldSupplier().get();
			ClientLevel level = Minecraft.getInstance().level;

			if (level == null)
			{
				PlacementManagerDaemonHandler.INSTANCE.clearAllTasks();
				return;
			}

			// The placement in this chunk is being moved (preview); it gets rebuilt once the move is committed
			if (manager.isChunkFrozenForMovePreview(this.cx(), this.cz()))
			{
				return;
			}

			if (manager.getAllSchematicsTouchingChunk(this.pos()).isEmpty())
			{
//				manager.removePendingRebuildFor(this.pos());
				PlacementManagerDaemonHandler.INSTANCE.removeAllTasksFor(this.cx(), this.cz());
//				manager.markChunkForUnload(this.cx(), this.cz());
				worldSchematic.getChunkSource().unloadChunk(this.cx(), this.cz());
				return;
			}

			if (manager.canHandleChunk(level, this.cx(), this.cz()))
			{
				if (worldSchematic.getChunkSource().hasChunk(this.cx(), this.cz()))
				{
					worldSchematic.unloadEntitiesByChunk(this.cx(), this.cz());
					worldSchematic.getChunkSource().unloadChunk(this.cx(), this.cz());
					manager.setVisibleSubChunksNeedsUpdate();
				}

				worldSchematic.getChunkSource().loadChunk(this.cx(), this.cz());
				manager.setVisibleSubChunksNeedsUpdate();
			}

			if (worldSchematic.getChunkSource().hasChunk(this.cx(), this.cz()))
			{
				ProtoChunkSchematic protoChunk = new ProtoChunkSchematic(new ChunkSchematic(worldSchematic, this.pos()));
				Collection<SchematicPlacement> placements = manager.getAllSchematicsTouchingChunk(this.pos());

				protoChunk.setState(ChunkSchematicState.PROTO);

				if (!placements.isEmpty())
				{
					SchematicPlacement single = null;
					BlockPos singleOrigin = null;
					int count = 0;

					for (SchematicPlacement placement : placements)
					{
						if (placement.isEnabled())
						{
							if (count++ == 0)
							{
								single = placement;
								singleOrigin = placement.getOrigin();
							}

							WorldPlacingUtils.placeToProtoChunk(protoChunk, this.pos(), placement);
						}
					}

					// Remember what got placed here, for the move preview
					manager.setChunkContent(this.cx(), this.cz(), count == 1 ? new SchematicPlacementManager.ChunkContent(single, singleOrigin) : null);

					// Load Real Chunk and spawn the entities
					worldSchematic.unloadEntitiesByChunk(this.cx(), this.cz());
					worldSchematic.getChunkSource().replaceChunk(this.cx(), this.cz(), protoChunk.getWrapped());
					protoChunk.spawnAllEntitiesNow(worldSchematic);
				}

				protoChunk.clear();

				// Note: a rebuild requested while this one was running is kept,
				// since it may have been requested for a newer placement state.

				worldSchematic.scheduleChunkRenders(this.cx(), this.cz());
				manager.setVisibleSubChunksNeedsUpdate();
			}
		};
	}
}
