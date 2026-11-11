package fi.dy.masa.litematica.scheduler.tasks;

import java.util.ArrayList;
import java.util.Collection;
import com.google.common.collect.ArrayListMultimap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;

import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.position.LayerRange;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.SchematicPlacingUtils;

public class TaskPasteSchematicPerChunkDirect extends TaskPasteSchematicPerChunkBase
{
    private static final long MAX_TICK_TIME_NANOS = 40_000_000L; // leave some headroom of the 50 ms tick
    private static final long MIN_BUDGET_NANOS    =  3_000_000L;
    private static final long MAX_BUDGET_NANOS    = 25_000_000L;
    private final ArrayListMultimap<ChunkPos, SchematicPlacement> placementsPerChunk = ArrayListMultimap.create();
    private long avgPasteNanos;

    public TaskPasteSchematicPerChunkDirect(Collection<SchematicPlacement> placements, LayerRange range, boolean changedBlocksOnly)
    {
        super(placements, range, changedBlocksOnly);
    }

    @Override
    public boolean canExecute()
    {
        return super.canExecute() &&
               this.mc.hasSingleplayerServer() &&
               this.world != null && this.world.isClientSide() == false;
    }

    @Override
    protected void onChunkAddedForHandling(ChunkPos pos, SchematicPlacement placement)
    {
        super.onChunkAddedForHandling(pos, placement);

        this.placementsPerChunk.put(pos, placement);
    }

    @Override
    public boolean execute(ProfilerFiller profiler)
    {
        // Nothing to do
        if (this.ignoreBlocks && this.ignoreEntities)
        {
            return true;
        }

        profiler.push("per_chunk_paste");

        MinecraftServer server = this.mc.getSingleplayerServer();
		if (server == null) return true;
        final long timeBudget = this.getTimeBudgetNanos(server);
        final long timeStart = Util.getNanos();

        this.sortChunkList();

        for (int chunkIndex = 0; chunkIndex < this.pendingChunks.size(); ++chunkIndex)
        {
            if (Util.getNanos() - timeStart >= timeBudget)
            {
                break;
            }

            profiler.push("process_chunk");

            ChunkPos pos = this.pendingChunks.get(chunkIndex);

            if (this.canProcessChunk(pos) && this.processChunk(pos))
            {
                this.pendingChunks.remove(chunkIndex);
                --chunkIndex;
            }

            profiler.pop();
        }

        long pasteTime = Util.getNanos() - timeStart;
        this.avgPasteNanos = this.avgPasteNanos == 0L ? pasteTime : (this.avgPasteNanos * 9L + pasteTime) / 10L;

        if (this.pendingChunks.isEmpty())
        {
            this.finished = true;
            profiler.pop();
            return true;
        }

        this.updateInfoHudLines();

        profiler.pop();
        return false;
    }

    /**
     * How long this tick may spend pasting. The rest of the server tick (and our own previous pasting)
     * is taken into account, so the server stays at (about) 20 TPS instead of
     * the paste using more than a whole tick (it used to be allowed up to 60 ms per tick).
     */
    private long getTimeBudgetNanos(MinecraftServer server)
    {
        long[] tickTimes = server.getTickTimesNanos();
        long sum = 0L;

        for (long time : tickTimes)
        {
            sum += time;
        }

        long avgTick = tickTimes.length > 0 ? sum / tickTimes.length : 0L;
        // Time the rest of the tick takes without us; roughly, since the average lags behind
        long otherWork = Math.max(0L, avgTick - this.avgPasteNanos);
        long budget = MAX_TICK_TIME_NANOS - otherWork;

        return Math.max(MIN_BUDGET_NANOS, Math.min(MAX_BUDGET_NANOS, budget));
    }

    @Override
    protected boolean processChunk(ChunkPos pos)
    {
        // TODO ignoreBlocks and ignoreEntities

        // New list to avoid CME
        ArrayList<SchematicPlacement> placements = new ArrayList<>(this.placementsPerChunk.get(pos));

        for (SchematicPlacement placement : placements)
        {
            if (SchematicPlacingUtils.placeToWorldWithinChunk(this.world, pos, placement, this.replace, this.layerBehavior, false))
            {
                this.placementsPerChunk.remove(pos, placement);
            }
        }

        return this.placementsPerChunk.containsKey(pos) == false;
    }

    @Override
    protected void onStop()
    {
        if (this.finished)
        {
            InfoUtils.showGuiOrActionBarMessage(MessageType.SUCCESS, "litematica.message.schematic_pasted");
        }
        else
        {
            InfoUtils.showGuiOrActionBarMessage(MessageType.ERROR, "litematica.message.error.schematic_paste_failed");
        }

        InfoHud.getInstance().removeInfoHudRenderer(this, false);

        super.onStop();
    }
}
