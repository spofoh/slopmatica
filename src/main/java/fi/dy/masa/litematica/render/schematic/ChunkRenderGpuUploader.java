package fi.dy.masa.litematica.render.schematic;

import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.logging.log4j.Logger;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.render.IWorldSchematicRenderer;
import fi.dy.masa.litematica.world.WorldSchematic;

public class ChunkRenderGpuUploader
{
	private static final Logger LOGGER = Litematica.LOGGER;
	protected volatile WorldSchematic world;
	protected final IWorldSchematicRenderer worldRenderer;
	protected final BlockPos.MutableBlockPos position;
	protected final BlockPos.MutableBlockPos chunkRelativePos;
	protected ChunkPos chunkPosition;
	private AABB boundingBox;

	private final GpuBufferCache gpuBufferCache;

	protected ChunkRenderGpuUploader(WorldSchematic world, IWorldSchematicRenderer worldRenderer)
	{
		this.world = world;
		this.worldRenderer = worldRenderer;
		this.position = new BlockPos.MutableBlockPos();
		this.chunkRelativePos = new BlockPos.MutableBlockPos();
		this.gpuBufferCache = new GpuBufferCache();
	}

	protected boolean isEmpty()
	{
		return this.gpuBufferCache.isEmpty();
	}

	protected boolean hasBuffers(ChunkSectionLayer layer)
	{
		return this.gpuBufferCache.hasBuffers(layer);
	}

	protected boolean hasBuffers(OverlayRenderType type)
	{
		return this.gpuBufferCache.hasBuffers(type);
	}

	protected @Nullable ChunkRenderGpuBuffers buffersOrNull(ChunkSectionLayer layer)
	{
		return this.gpuBufferCache.getBuffersOrNull(layer);
	}

	protected @Nullable ChunkRenderGpuBuffers buffersOrNull(OverlayRenderType type)
	{
		return this.gpuBufferCache.getBuffersOrNull(type);
	}

	protected GpuBufferCache gpuBufferCache()
	{
		return this.gpuBufferCache;
	}

	public BlockPos origin()
	{
		return this.position.immutable();
	}

	protected ChunkPos chunkPos()
	{
		if (this.chunkPosition == null)
		{
			this.chunkPosition = ChunkPos.containing(this.position.immutable());
		}

		return this.chunkPosition;
	}

	public AABB boundingBox()
	{
		if (this.boundingBox == null)
		{
			int x = this.position.getX();
			int y = this.position.getY();
			int z = this.position.getZ();
			this.boundingBox = new AABB(x, y, z, x + 16, y + this.world.getHeight(), z + 16);
		}

		return this.boundingBox;
	}

	protected void setPosition(int x, int y, int z)
	{
		if (x != this.position.getX() ||
			y != this.position.getY() ||
			z != this.position.getZ())
		{
			this.clear();
			this.position.set(x, y, z);
			this.chunkPosition = ChunkPos.containing(this.position.immutable());
			this.boundingBox = new AABB(x, y, z, x + 16, y + this.world.getHeight(), z + 16);
		}
	}

	protected VertexSorting createVertexSorter(float x, float y, float z)
	{
		return VertexSorting.byDistance(x, y, z);
	}

	protected VertexSorting createVertexSorter(Vec3 pos)
	{
		return VertexSorting.byDistance((float) pos.x(), (float) pos.y(), (float) pos.z());
	}

	protected VertexSorting createVertexSorter(Vec3 pos, BlockPos origin)
	{
		return VertexSorting.byDistance((float) (pos.x - (double) origin.getX()), (float) (pos.y - (double) origin.getY()), (float) (pos.z - (double) origin.getZ()));
	}

	protected VertexSorting createVertexSorter(Camera camera)
	{
		Vec3 vec3d = camera.position();

		return this.createVertexSorter(vec3d, this.origin());
	}

	protected void uploadBuffersByLayer(ChunkSectionLayer layer, @Nonnull MeshData meshData, boolean useResorting)
	{
//        LOGGER.warn("[GPU] uploadBuffersByLayer() Layer [{}], IndexCount [{}]", layer.label(), meshData.drawState().indexCount());
		// Always upload into new buffers; the old ones get closed by saveBuffers() once replaced.
		// (Reusing them closed the buffers without replacing them, making chunks disappear after a rebuild)
		GpuDevice device = RenderSystem.getDevice();
		Supplier<String> name = layer::label;
//            LOGGER.warn("[GPU] uploadBuffersByLayer() Layer [{}], NEW VERTEX BUFFER", layer.label());
		GpuBuffer vertexBuffer =
				device.createBuffer(
						() -> "VertexBuffer: " + name.get() + " VBO Section: [" + this.chunkRelativePos.toShortString() + "]",
						40, meshData.vertexBuffer()
				);
		GpuBuffer indexBuffer =
				meshData.indexBuffer() != null && useResorting ?
				device.createBuffer(
						() -> "IndexBuffer: " + name.get() + " VBO Section: [" + this.chunkRelativePos.toShortString() + "]",
						72, meshData.indexBuffer()
				) : null;

//            LOGGER.warn("[GPU] uploadBuffersByLayer() Layer [{}], NEW VERTEX BUFFER --> SAVE", layer.label());
		this.gpuBufferCache.saveBuffers(layer,
		                                new ChunkRenderGpuBuffers(name, vertexBuffer, indexBuffer,
		                                                          meshData.drawState().indexCount(),
		                                                          meshData.drawState().indexType())
		);


//        LOGGER.warn("[GPU] uploadBuffersByLayer() Layer [{}], END", layer.label());
        meshData.close();
	}

	protected void uploadBuffersByType(OverlayRenderType type, @Nonnull MeshData meshData, boolean useResorting)
	{
//        LOGGER.warn("[GPU] uploadBuffersByType() Overlay [{}], IndexCount [{}]", type.name(), meshData.drawState().indexCount());
		// Always upload into new buffers; the old ones get closed by saveBuffers() once replaced.
		// (Reusing them closed the buffers without replacing them, making chunks disappear after a rebuild)
		GpuDevice device = RenderSystem.getDevice();
		Supplier<String> name = type::name;
//            LOGGER.warn("[GPU] uploadBuffersByType() Overlay [{}], NEW VERTEX BUFFER", type.name());
		GpuBuffer vertexBuffer =
				device.createBuffer(
						() -> "VertexBuffer: Overlay/" + name.get() + " VBO Section: [" + this.chunkRelativePos.toShortString() + "]",
						40, meshData.vertexBuffer()
				);
		GpuBuffer indexBuffer =
				meshData.indexBuffer() != null && useResorting ?
                    RenderSystem.getDevice()
                                .createBuffer(() -> "IndexBuffer: " + name.get() + " VBO Section: [" + this.chunkRelativePos.toShortString() + "]",
                                              72, meshData.indexBuffer()
                                ) : null;

//            LOGGER.warn("[GPU] uploadBuffersByType() Overlay [{}], NEW VERTEX BUFFER --> SAVE", type.name());
		this.gpuBufferCache.saveBuffers(type,
		                                new ChunkRenderGpuBuffers(name, vertexBuffer, indexBuffer,
		                                                          meshData.drawState().indexCount(),
		                                                          meshData.drawState().indexType())
		);


//        LOGGER.warn("[GPU] uploadBuffersByType() Overlay [{}], END", type.name());
        meshData.close();
	}

	protected void uploadIndexByBlockLayer(ChunkSectionLayer layer, @Nonnull ByteBufferBuilder.Result buffer)
	{
//        LOGGER.warn("[GPU] uploadIndexByLayer() Layer [{}] --> BEGIN", layer.label());
		if (this.hasBuffers(layer))
		{
			ChunkRenderGpuBuffers gpuBuffers = this.buffersOrNull(layer);
			GpuDevice device = RenderSystem.getDevice();

			assert gpuBuffers != null;
			if (gpuBuffers.indexBuffer == null)
			{
//                LOGGER.warn("[GPU] uploadIndexByLayer() Layer [{}] --> SET INDEX BUFFER", layer.label());
				gpuBuffers.setIndexBuffer(
						device.createBuffer(
								() -> "IndexBuffer: " + gpuBuffers.getName() + " VBO Section: [" + this.chunkRelativePos.toShortString() + "]",
								72, buffer.byteBuffer()
						));
			}
			else
			{
				if (!gpuBuffers.indexBuffer.isClosed())
				{
//                    LOGGER.warn("[GPU] uploadIndexByLayer() Layer [{}] --> WRITE INDEX BUFFER", layer.label());
					device.createCommandEncoder()
					      .writeToBuffer(gpuBuffers.indexBuffer.slice(), buffer.byteBuffer());
				}
			}
		}

//        LOGGER.warn("[GPU] uploadIndexByLayer() Layer [{}] --> END", layer.label());
        buffer.close();
	}

	protected void uploadIndexByType(OverlayRenderType type, @Nonnull ByteBufferBuilder.Result buffer)
	{
//        LOGGER.warn("[GPU] uploadIndexByType() Overlay [{}] --> BEGIN", type.name());
		if (this.hasBuffers(type))
		{
			ChunkRenderGpuBuffers gpuBuffers = this.buffersOrNull(type);
			GpuDevice device = RenderSystem.getDevice();

			assert gpuBuffers != null;
			if (gpuBuffers.indexBuffer == null)
			{
//                LOGGER.warn("[GPU] uploadIndexByType() Overlay [{}] --> SET INDEX BUFFER", type.name());
				gpuBuffers.setIndexBuffer(
						device.createBuffer(
								() -> "IndexBuffer: Overlay/" + gpuBuffers.getName() + " VBO Section: [" + this.chunkRelativePos.toShortString() + "]",
								72, buffer.byteBuffer()
						));
			}
			else
			{
				if (!gpuBuffers.indexBuffer.isClosed())
				{
//                    LOGGER.warn("[GPU] uploadIndexByType() Overlay [{}] --> WRITE INDEX BUFFER", type.name());
					device.createCommandEncoder()
					      .writeToBuffer(gpuBuffers.indexBuffer.slice(), buffer.byteBuffer());
				}
			}
		}

//        LOGGER.warn("[GPU] uploadIndexByType() Overlay [{}] --> END", type.name());
        buffer.close();
	}

	protected void clear()
	{
		this.gpuBufferCache.clearAll();
	}
}
