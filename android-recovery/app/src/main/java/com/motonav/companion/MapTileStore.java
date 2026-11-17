package com.motonav.companion;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;

/** Visible-tile downloads only, with seven-day private caching and bounded I/O. */
public final class MapTileStore {
    public static final String TEMPLATE="https://vector.openstreetmap.org/shortbread_v1/{z}/{x}/{y}.mvt";
    private static final int MAX_BYTES=4*1024*1024;
    private static final long CACHE_MS=7L*24*60*60*1000;
    private final Path directory;
    private final String template,userAgent;
    interface Fetcher { byte[] fetch(Basemap.Tile tile) throws IOException; }
    private final Fetcher fetcher;
    public record Read(byte[] bytes,boolean stale) { }
    public MapTileStore(Path directory,String template,String userAgent) {
        this(directory,template,userAgent,null);
    }
    MapTileStore(Path directory,String template,String userAgent,Fetcher fetcher) {
        if(!template.startsWith("https://")||!template.contains("{z}")||!template.contains("{x}")||!template.contains("{y}"))throw new IllegalArgumentException("tile endpoint");
        this.directory=directory;this.template=template;this.userAgent=userAgent;this.fetcher=fetcher;
    }
    public byte[] get(Basemap.Tile tile) throws IOException {
        return read(tile).bytes();
    }
    public Read read(Basemap.Tile tile) throws IOException {
        Files.createDirectories(directory);
        Path path=directory.resolve(tile.key().replace('/','_')+".mvt");long now=System.currentTimeMillis();
        byte[] previous=null;
        if(Files.isRegularFile(path)&&Files.size(path)<=MAX_BYTES){
            byte[] cached=Files.readAllBytes(path);
            try{VectorTile.decode(cached);previous=cached;}
            catch(IllegalArgumentException invalid){Files.deleteIfExists(path);}
            if(previous!=null&&now-Files.getLastModifiedTime(path).toMillis()<CACHE_MS)return new Read(previous,false);
        }
        try{
            reserve(path,now);
            byte[] bytes=fetcher==null?download(tile):fetcher.fetch(tile);
            try{VectorTile.decode(bytes);}catch(IllegalArgumentException invalid){throw new IOException("Map tile invalid",invalid);}
            Path temporary=path.resolveSibling(path.getFileName()+".tmp");
            Files.write(temporary,bytes);Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);
            return new Read(bytes,false);
        }catch(IOException failure){
            // An expired but validated map is still useful when the provider
            // is offline. Preserve its timestamp so it will be retried.
            if(previous!=null)return new Read(previous,true);
            throw failure;
        }
    }
    private void reserve(Path replacing,long now)throws IOException {
        long used=0;java.util.List<Path> expired=new java.util.ArrayList<>();
        try(var files=Files.list(directory)){for(Path item:files.toArray(Path[]::new)){
            if(!Files.isRegularFile(item)||!item.getFileName().toString().endsWith(".mvt")||item.equals(replacing))continue;
            used+=Files.size(item);
            if(now-Files.getLastModifiedTime(item).toMillis()>=CACHE_MS)expired.add(item);
        }}
        // Only evict expired cache entries when space is actually required.
        // Never delete every old region before knowing a download succeeded.
        for(Path item:expired){if(used<=128L*1024*1024-MAX_BYTES)break;long bytes=Files.size(item);if(Files.deleteIfExists(item))used-=bytes;}
        if(used>128L*1024*1024-MAX_BYTES)throw new IOException("Map cache full");
    }
    private byte[] download(Basemap.Tile tile)throws IOException {
        URL url=new URL(template.replace("{z}",Integer.toString(tile.zoom())).replace("{x}",Integer.toString(tile.x())).replace("{y}",Integer.toString(tile.y())));
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(8000);connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent",userAgent);connection.setRequestProperty("Accept-Encoding","gzip");
        try {
            if(connection.getResponseCode()!=200)throw new IOException("Map HTTP "+connection.getResponseCode());
            byte[] bytes;
            try(InputStream raw=connection.getInputStream();InputStream stream="gzip".equalsIgnoreCase(connection.getContentEncoding())?new GZIPInputStream(raw):raw) {
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;
                while((read=stream.read(buffer))!=-1){if(out.size()+read>MAX_BYTES)throw new IOException("Map tile too large");out.write(buffer,0,read);}bytes=out.toByteArray();
            }
            return bytes;
        } finally {connection.disconnect();}
    }
}
