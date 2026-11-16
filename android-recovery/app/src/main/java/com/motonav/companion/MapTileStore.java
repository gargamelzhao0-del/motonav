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
    public MapTileStore(Path directory,String template,String userAgent) {
        if(!template.startsWith("https://")||!template.contains("{z}")||!template.contains("{x}")||!template.contains("{y}"))throw new IllegalArgumentException("tile endpoint");
        this.directory=directory;this.template=template;this.userAgent=userAgent;
    }
    public byte[] get(Basemap.Tile tile) throws IOException {
        Files.createDirectories(directory);
        Path path=directory.resolve(tile.key().replace('/','_')+".mvt");long now=System.currentTimeMillis();
        if(Files.isRegularFile(path)&&Files.size(path)<=MAX_BYTES&&now-Files.getLastModifiedTime(path).toMillis()<CACHE_MS)return Files.readAllBytes(path);
        long used=0;
        try(var files=Files.list(directory)) { for(Path item:files.toArray(Path[]::new)) {
            if(now-Files.getLastModifiedTime(item).toMillis()>CACHE_MS)Files.deleteIfExists(item);
            else used+=Files.size(item);
        } }
        // Retain fresh tiles for the provider's minimum seven-day lifetime.
        if(used>128L*1024*1024-MAX_BYTES)throw new IOException("Map cache full");
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
            // Validate before caching; failed downloads cannot poison subsequent views.
            VectorTile.decode(bytes);
            Path temporary=path.resolveSibling(path.getFileName()+".tmp");Files.write(temporary,bytes);Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);
            // Expired entries may be removed. Fresh tiles retain the minimum cache lifetime.
            return bytes;
        } finally {connection.disconnect();}
    }
}
