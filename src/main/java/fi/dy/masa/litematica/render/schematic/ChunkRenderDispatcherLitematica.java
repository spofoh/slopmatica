package fi.dy.masa.litematica.render.schematic;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.common.collect.Queues;
import com.google.common.primitives.Doubles;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import org.apache.logging.log4j.Logger;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.config.Configs;

public class ChunkRenderDispatcherLitematica
{
    private static final Logger LOGGER = Litematica.LOGGER;

    // Chunk rebuilds (meshing); processed by the background worker threads
    private final PriorityBlockingQueue<ChunkRenderTaskSchematic> queueChunkUpdates;
    // Translucent re-sorts; processed on the render thread, since they touch the live mesh data
    private final PriorityBlockingQueue<ChunkRenderTaskSchematic> queueResorts;
    private final Queue<PendingUpload> queueChunkUploads;
	private final Queue<ChunkRenderDispatcherBuffers> freeBufferPacks;
    private final ChunkRenderWorkerLitematica renderWorker;
    private final List<Thread> workerThreads = new ArrayList<>();
    private final AtomicInteger activeWorkerTasks = new AtomicInteger();
    private volatile boolean running = true;
    private volatile Vec3 cameraPos;

	protected ChunkRenderDispatcherLitematica()
    {
		this.queueChunkUpdates = Queues.newPriorityBlockingQueue();
		this.queueResorts = Queues.newPriorityBlockingQueue();
		this.queueChunkUploads = Queues.newPriorityQueue();
	    this.freeBufferPacks = Queues.newConcurrentLinkedQueue();
        this.cameraPos = Vec3.ZERO;
        this.renderWorker = new ChunkRenderWorkerLitematica(this);
        this.startWorkerThreads();
    }

    public static int getWorkerThreadCount()
    {
        int configured = Configs.Visuals.RENDER_SCHEMATIC_THREADS.getIntegerValue();

        if (configured > 0)
        {
            return configured;
        }

        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(6, (cores - 2) / 3));
    }

    protected int getWorkerCount()
    {
        return this.workerThreads.size();
    }

    private void startWorkerThreads()
    {
        final int count = getWorkerThreadCount();

        for (int i = 0; i < count; i++)
        {
            Thread thread = new Thread(this::workerLoop, "Litematica Chunk Builder " + (i + 1));
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            thread.start();
            this.workerThreads.add(thread);
        }

        LOGGER.info("Started [{}] schematic chunk builder threads", count);
    }

    private void workerLoop()
    {
        while (this.running)
        {
            ChunkRenderTaskSchematic task;

            try
            {
                task = this.queueChunkUpdates.take();
            }
            catch (InterruptedException e)
            {
                continue;
            }

            this.activeWorkerTasks.incrementAndGet();

            try
            {
                this.renderWorker.processTask(task);
            }
            catch (InterruptedException ignored)
            {
            }
            catch (Throwable t)
            {
                LOGGER.error("Exception while building a schematic chunk mesh: {}", t.toString());
                task.finish();
            }
            finally
            {
                this.activeWorkerTasks.decrementAndGet();
            }
        }
    }

    /**
     * True when no chunk meshes are being built or waiting to be uploaded.
     */
    protected boolean isIdle()
    {
        synchronized (this.queueChunkUploads)
        {
            if (!this.queueChunkUploads.isEmpty())
            {
                return false;
            }
        }

        return this.queueChunkUpdates.isEmpty() && this.activeWorkerTasks.get() == 0;
    }

	protected ChunkRenderDispatcherBuffers allocateBufferPack()
	{
		ChunkRenderDispatcherBuffers pack = this.freeBufferPacks.poll();
		return pack != null ? pack : new ChunkRenderDispatcherBuffers();
	}

	protected void freeBufferPack(ChunkRenderDispatcherBuffers pack)
	{
		pack.reset();
		this.freeBufferPacks.offer(pack);
	}

    protected void setCameraPosition(Vec3 cameraPos)
    {
        this.cameraPos = cameraPos;
    }

    public Vec3 getCameraPos()
    {
        return this.cameraPos;
    }

    protected String getDebugInfo()
    {
	    return String.format("pC: %03d, pU: %03d, W: %d/%d, aB: %1d", this.queueChunkUpdates.size(), this.queueChunkUploads.size(),
	                         this.activeWorkerTasks.get(), this.workerThreads.size(), this.freeBufferPacks.size());
    }

    protected boolean runChunkUploads(long finishTimeNano)
    {
        boolean ranTasks = false;
        //LOGGER.warn("[Dispatch] runChunkUploads() - queueChunkUpdates({}) // queueChunkUploads({})", this.queueChunkUpdates.size(), this.queueChunkUploads.size());
//        profiler.push("run_chunk_uploads");

        while (true)
        {
            boolean processedTask = false;

            // Only the translucent resorting runs here, the meshing is done by the worker threads
            ChunkRenderTaskSchematic generator = this.queueResorts.poll();

            if (generator != null)
            {
                try
                {
                    this.renderWorker.processTask(generator);
                    processedTask = true;
                }
                catch (InterruptedException e)
                {
                    LOGGER.warn("runChunkUploads(): Process Interrupted; error message: [{}]", e.getLocalizedMessage());
                }
            }

            synchronized (this.queueChunkUploads)
            {
                if (!this.queueChunkUploads.isEmpty())
                {
//                    (this.queueChunkUploads.poll()).uploadTask.run();
                    PendingUpload upload = this.queueChunkUploads.poll();

                    if (upload != null)
                    {
                        upload.uploadTask.run();
                        processedTask = true;
                    }

                    ranTasks = true;
                }
            }

            if (finishTimeNano == 0L || processedTask == false || finishTimeNano < System.nanoTime())
            {
                break;
            }
        }

//        profiler.pop();
        return ranTasks;
    }

    protected boolean updateChunkLater(ChunkRendererSchematicVbo renderChunk)
    {
//        LOGGER.warn("[Dispatch] updateChunkLater()");
//        profiler.push("update_chunk_later");

        renderChunk.getLockCompileTask().lock();
        boolean flag1;

        try
        {
            // Already queued, and it will use the latest world state
            if (renderChunk.hasPendingRebuild())
            {
                return true;
            }

            final ChunkRenderTaskSchematic generator = renderChunk.makeCompileTaskChunkSchematic(this::getCameraPos);

            generator.addFinishRunnable(() -> ChunkRenderDispatcherLitematica.this.queueChunkUpdates.remove(generator));

            boolean flag = this.queueChunkUpdates.offer(generator);

            if (!flag)
            {
                generator.finish();
            }

            flag1 = flag;
        }
        finally
        {
            renderChunk.getLockCompileTask().unlock();
        }

//        profiler.pop();
        return flag1;
    }

    /**
     * "Immediate" updates (for example from block changes) are queued
     * for the worker threads with the highest priority,
     * instead of building the whole chunk column on the render thread.
     */
    protected boolean updateChunkNow(ChunkRendererSchematicVbo chunkRenderer)
    {
        chunkRenderer.getLockCompileTask().lock();
        boolean flag;

        try
        {
            // Already queued, and it will use the latest world state
            if (chunkRenderer.hasPendingRebuild())
            {
                return true;
            }

            final ChunkRenderTaskSchematic generator = chunkRenderer.makeCompileTaskChunkSchematic(this::getCameraPos, true);

            generator.addFinishRunnable(() -> ChunkRenderDispatcherLitematica.this.queueChunkUpdates.remove(generator));

            flag = this.queueChunkUpdates.offer(generator);

            if (!flag)
            {
                generator.finish();
            }
        }
        finally
        {
            chunkRenderer.getLockCompileTask().unlock();
        }

        return flag;
    }

    protected void stopChunkUpdates()
    {
//        LOGGER.warn("[Dispatch] stopChunkUpdates()");
//        profiler.push("stop_chunk_updates");
        this.clearChunkUpdates();
        this.runChunkUploads(Long.MAX_VALUE);
//        profiler.pop();
    }

    /**
     * Queues a task that needs to run on the render thread, ordered with the uploads.
     */
    protected ListenableFuture<Object> scheduleOnRenderThread(Runnable runnable, final double distanceSq)
    {
        ListenableFutureTask<Object> futureTask = ListenableFutureTask.create(runnable, null);

        synchronized (this.queueChunkUploads)
        {
            this.queueChunkUploads.add(new PendingUpload(futureTask, distanceSq));
        }

        return futureTask;
    }

    protected boolean updateTransparencyLater(ChunkRendererSchematicVbo renderChunk)
    {
//        profiler.push("update_transparency_later");

        renderChunk.getLockCompileTask().lock();
        boolean flag;

        try
        {
            final ChunkRenderTaskSchematic generator = renderChunk.makeCompileTaskTransparencySchematic(this::getCameraPos);

            if (generator == null)
            {
                flag = true;
//                profiler.pop();
                return flag;
            }

            generator.addFinishRunnable(() -> ChunkRenderDispatcherLitematica.this.queueResorts.remove(generator));

            flag = this.queueResorts.offer(generator);
        }
        finally
        {
            renderChunk.getLockCompileTask().unlock();
        }

//        profiler.pop();
        return flag;
    }

    protected ListenableFuture<Object> uploadChunkBlocks(final ChunkSectionLayer layer, final ChunkRenderDataSchematic compiledChunk,
                                                         ChunkRenderDispatcherBuffers pack, ChunkRenderGpuUploader uploader,
                                                         final double distanceSq, boolean resortOnly)
    {
//        LOGGER.warn("[Dispatch] uploadChunkBlocks layer [{}]", layer.label());
//        profiler.push("upload_chunk_blocks");
        if (Minecraft.getInstance().isSameThread())
        {
            try
            {
                this.uploadVertexBufferByBlockLayer(layer, compiledChunk, pack, uploader, uploader.createVertexSorter(this.getCameraPos(), uploader.origin()), resortOnly);
            }
            catch (Exception e)
            {
                LOGGER.warn("uploadChunkBlocks(): [Dispatch] Error uploading Vertex Buffer for layer [{}], Caught error: [{}]", layer.label(), e.toString());
            }

//            profiler.pop();
            return Futures.immediateFuture(null);
        }
        else
        {
//            profiler.popPush("upload_chunk_blocks_later");

//            ListenableFutureTask<Object> futureTask = ListenableFutureTask.create(() -> ChunkRenderDispatcherLitematica.this.uploadChunkBlocks(layer, renderChunk, compiledChunk, pack, distanceSq, resortOnly), null);
	        ListenableFutureTask<Object> futureTask = ListenableFutureTask.create(() ->
	                                                                              {
																					  try
																					  {
																						  this.uploadVertexBufferByBlockLayer(layer, compiledChunk, pack, uploader, uploader.createVertexSorter(this.getCameraPos(), uploader.origin()), resortOnly);
																					  }
																					  catch (Exception e)
																					  {
																						  LOGGER.error("uploadChunkBlocks() -> [Dispatch] uploadVertexBufferByBlockLayer: Exception: {}", e.getLocalizedMessage());
																					  }
	                                                                              }, null);

            synchronized (this.queueChunkUploads)
            {
                this.queueChunkUploads.add(new PendingUpload(futureTask, distanceSq));
//                profiler.pop();
                return futureTask;
            }
        }
    }

    protected ListenableFuture<Object> uploadChunkOverlay(final OverlayRenderType type, final ChunkRenderDataSchematic compiledChunk,
                                                          ChunkRenderDispatcherBuffers pack, ChunkRenderGpuUploader uploader,
                                                          final double distanceSq, boolean resortOnly)
    {
//        LOGGER.warn("[Dispatch] uploadChunkOverlay type [{}]", type.name());
//        profiler.push("upload_chunk_overlay");

        if (Minecraft.getInstance().isSameThread())
        {
            try
            {
                this.uploadVertexBufferByType(type, compiledChunk, pack, uploader, uploader.createVertexSorter(this.getCameraPos(), uploader.origin()), resortOnly);
            }
            catch (Exception e)
            {
                // TODO --> This one will throw if it's not sorted as Translucent,
                //  but it will cause a crash during draw() --> Ignored
                LOGGER.error("uploadChunkOverlay(): [Dispatch] Error uploading Vertex Buffer for overlay type [{}], Caught error: [{}]", type.name(), e.toString());
            }

//            profiler.pop();
            return Futures.immediateFuture(null);
        }
        else
        {
//            profiler.popPush("upload_chunk_overlay_later");
//            ListenableFutureTask<Object> futureTask = ListenableFutureTask.<Object>create(() -> ChunkRenderDispatcherLitematica.this.uploadChunkOverlay(type, renderChunk, compiledChunk, pack, distanceSq, resortOnly), null);
	        ListenableFutureTask<Object> futureTask = ListenableFutureTask.<Object>create(() ->
	                                                                                      {
																							  try
																							  {
																								  this.uploadVertexBufferByType(type, compiledChunk, pack, uploader, uploader.createVertexSorter(this.getCameraPos(), uploader.origin()), resortOnly);
																							  }
																							  catch (Exception e)
																							  {
																								  LOGGER.error("uploadChunkOverlay() -> [Dispatch] uploadVertexBufferByType: Exception: {}", e.getLocalizedMessage());;
																							  }
	                                                                                      }, null);

            synchronized (this.queueChunkUploads)
            {
                this.queueChunkUploads.add(new PendingUpload(futureTask, distanceSq));
//                profiler.pop();
                return futureTask;
            }
        }
    }

    private void uploadVertexBufferByBlockLayer(final ChunkSectionLayer layer, final ChunkRenderDataSchematic compiledChunk,
                                                ChunkRenderDispatcherBuffers pack, ChunkRenderGpuUploader uploader,
                                                final VertexSorting sorter, boolean resortOnly)
            throws InterruptedException
    {
//        LOGGER.warn("[Dispatch] uploadVertexBufferByBlockLayer layer [{}]", layer.label());
//        profiler.push("upload_vbo_layer_"+layer.label());

        ByteBufferBuilder allocator = pack.allocatorCache().getAllocator(layer);
        final ChunkMeshDataSchematic chunkMeshData = compiledChunk.getMeshDataCache();
        MeshData meshData = chunkMeshData.getMeshDataOrNull(layer);
        boolean useResorting = Configs.Visuals.RENDER_ENABLE_TRANSLUCENT_RESORTING.getBooleanValue();

        if (allocator == null)
        {
            LOGGER.error("[Dispatch] uploadVertexBufferByBlockLayer layer [{}] --> ALLOC NULL", layer.label());
	        pack.allocatorCache().closeByBlockLayer(layer);
            compiledChunk.setBlockLayerUnused(layer);
//            profiler.pop();
            throw new InterruptedException("BufferAllocators are invalid");
        }

        if (meshData == null)
        {
            LOGGER.error("[Dispatch] uploadVertexBufferByBlockLayer layer [{}] --> MESHDATA NULL", layer.label());
            compiledChunk.setBlockLayerUnused(layer);
//            profiler.pop();
            return;
        }

        if (resortOnly == false)
        {
//            LOGGER.error("[Dispatch] uploadVertexBufferByBlockLayer layer [{}] --> UPLOAD", layer.label());
            uploader.uploadBuffersByLayer(layer, meshData, useResorting);
        }

        if (layer == ChunkSectionLayer.TRANSLUCENT && useResorting)
        {
            MeshData.SortState sorting = chunkMeshData.getTransparentSortingDataForBlockLayer(layer);

            if (sorting == null)
            {
                sorting = meshData.sortQuads(allocator, sorter);

                if (sorting == null)
                {
//                    profiler.pop();
                    LOGGER.error("[Dispatch] uploadVertexBufferByBlockLayer layer [{}] --> SORT FAILURE", layer.label());
                    throw new InterruptedException("Sort State failed to sortQuads()");
                }

                chunkMeshData.setTransparentSortingDataForBlockLayer(layer, sorting);
            }

            ByteBufferBuilder.Result result = sorting.buildSortedIndexBuffer(allocator, sorter);

            if (result != null)
            {
//                LOGGER.warn("[Dispatch] uploadVertexBufferByBlockLayer layer [{}] --> UPLOAD INDEX", layer.label());
                uploader.uploadIndexByBlockLayer(layer, result);
                result.close();
            }
        }

//        LOGGER.warn("[Dispatch] uploadVertexBufferByBlockLayer layer [{}] --> DONE", layer.label());
//        profiler.pop();
    }

    private void uploadVertexBufferByType(final OverlayRenderType type, final ChunkRenderDataSchematic compiledChunk,
                                          ChunkRenderDispatcherBuffers pack, ChunkRenderGpuUploader uploader,
                                          final VertexSorting sorter, boolean resortOnly)
            throws InterruptedException
    {
//        LOGGER.warn("[Dispatch] uploadVertexBufferByType type [{}]", type.name());
//        profiler.push("upload_vbo_overlay_"+type.name());
        ByteBufferBuilder allocator = pack.allocatorCache().getAllocator(type);
        final ChunkMeshDataSchematic chunkMeshData = compiledChunk.getMeshDataCache();
        MeshData meshData = chunkMeshData.getMeshDataOrNull(type);
        boolean useResorting = false;

        if (allocator == null)
        {
            LOGGER.error("[Dispatch] uploadVertexBufferByType type [{}] --> ALLOC NULL", type.name());
	        pack.allocatorCache().closeByType(type);
            compiledChunk.setOverlayTypeUnused(type);
//            profiler.pop();
            throw new InterruptedException("BufferAllocators are invalid");
        }

        if (meshData == null)
        {
            LOGGER.error("[Dispatch] uploadVertexBufferByType type [{}] --> MESHDATA NULL", type.name());
            compiledChunk.setOverlayTypeUnused(type);
//            profiler.pop();
            return;
        }

        if (resortOnly == false)
        {
//            LOGGER.warn("[Dispatch] uploadVertexBufferByType type [{}] --> UPLOAD", type.name());
            uploader.uploadBuffersByType(type, meshData, useResorting);
        }

        if (type.translucent() && useResorting)
        {
            MeshData.SortState sorting = chunkMeshData.getTransparentSortingDataForOverlay(type);

            if (sorting == null)
            {
                sorting = meshData.sortQuads(allocator, sorter);

                if (sorting == null)
                {
//                    profiler.pop();
                    //LOGGER.warn("[Dispatch] uploadVertexBufferByType type [{}] --> SORT FAILURE", type.name());
                    throw new InterruptedException("Sort State failed to sortQuads()");
                }

                chunkMeshData.setTransparentSortingDataForOverlay(type, sorting);
            }

            ByteBufferBuilder.Result result = sorting.buildSortedIndexBuffer(allocator, sorter);

            if (result != null)
            {
                //LOGGER.warn("[Dispatch] uploadVertexBufferByType type [{}] --> UPLOAD INDEX", type.name());
                uploader.uploadIndexByType(type, result);
                result.close();
            }
        }

//        LOGGER.warn("[Dispatch] uploadVertexBufferByType type [{}] --> DONE", type.name());
//        profiler.pop();
    }

    protected void clearChunkUpdates()
    {
        while (this.queueChunkUpdates.isEmpty() == false)
        {
            // Only the translucent resorting runs here, the meshing is done by the worker threads
            ChunkRenderTaskSchematic generator = this.queueResorts.poll();

            if (generator != null)
            {
                generator.finish();
            }
        }
    }

    public boolean hasChunkUpdates()
    {
        return this.queueChunkUpdates.isEmpty() && this.queueChunkUploads.isEmpty();
    }

    protected void stopWorkerThreads()
    {
//        LOGGER.warn("[Dispatch] stopWorkerThreads()");
        this.running = false;
        this.clearChunkUpdates();

        for (Thread thread : this.workerThreads)
        {
            thread.interrupt();
        }

        this.workerThreads.clear();
    }

    protected static class PendingUpload implements Comparable<PendingUpload>
    {
        private final ListenableFutureTask<Object> uploadTask;
        private final double distanceSq;

        public PendingUpload(ListenableFutureTask<Object> uploadTaskIn, double distanceSqIn)
        {
            this.uploadTask = uploadTaskIn;
            this.distanceSq = distanceSqIn;
        }

        public int compareTo(PendingUpload other)
        {
            return Doubles.compare(this.distanceSq, other.distanceSq);
        }
    }
}
