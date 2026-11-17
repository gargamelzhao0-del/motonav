package com.motonav.companion;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Per-tile failure isolation. One bad tile cannot suppress its valid neighbors. */
public final class MapRegionLoader {
    public record TileData(VectorTile.Decoded decoded,boolean stale) { }
    public record Result(Basemap.Scene scene,MapQuality quality) {
        /** Equal coverage during a retry is only authoritative after every tile
         * was attempted. Its early 192-item scene must not replace a retained
         * 384-item result from the same one successfully loaded tile. */
        public boolean mayReplace(int retainedTiles){
            return quality.tilesLoaded()>0&&quality.tilesLoaded()>=retainedTiles
                &&(retainedTiles==0||quality.tilesLoaded()>retainedTiles
                    ||quality.tilesLoaded()+quality.tilesFailed()==quality.tilesRequired());
        }
    }
    public interface Source { TileData read(Basemap.Tile tile) throws IOException; }
    private MapRegionLoader() { }
    public static Result load(List<Basemap.Tile> tiles,LocalMapBuilder builder,int revision,int limit,
            Source source,Consumer<Result> progress,BooleanSupplier cancelled) throws InterruptedException {
        if(tiles.isEmpty()||tiles.size()>9)throw new IllegalArgumentException("tile count");
        int loaded=0,stale=0,failed=0;Result result=null;
        for(var tile:tiles){
            if(cancelled.getAsBoolean())throw new InterruptedException();
            TileData data=null;
            try{data=source.read(tile);}catch(IOException|IllegalArgumentException|LinkageError error){failed++;}
            if(cancelled.getAsBoolean())throw new InterruptedException();
            if(data!=null){
                builder.add(tile,data.decoded());loaded++;if(data.stale())stale++;
            }
            // Subsequent tiles refine the full budget; only the first useful
            // tile has the smaller initial transfer. Failed tiles are counted.
            int budget=loaded==1&&loaded+failed<tiles.size()?Math.min(192,limit):limit;
            var scene=builder.finish(revision,budget);
            var quality=builder.quality(tiles.size(),loaded,stale,failed,scene);
            result=new Result(new Basemap.Scene(revision,1,quality.downloaded(),scene.items(),scene.labels()),quality);
            if(progress!=null)progress.accept(result);
        }
        return result;
    }
}
