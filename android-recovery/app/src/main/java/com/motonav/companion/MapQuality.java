package com.motonav.companion;

/** Counts only: no location, tile IDs, feature names, endpoints or credentials. */
public record MapQuality(int tilesRequired,int tilesLoaded,int tilesStale,int tilesFailed,
        int sourceFeatures,int sourceRoads,int sourceBuildings,int sourceLabels,int ignoredFeatures,
        int geometryCandidates,int geometrySelected,int oversizedGroups,int geometryDuplicates,
        int labelCandidates,int labelsSelected,int labelDuplicates) {
    public boolean downloaded(){return tilesLoaded==tilesRequired&&tilesFailed==0&&tilesStale==0;}
    public String summary(){
        return "底图瓦片 "+tilesLoaded+"/"+tilesRequired+" · 失败 "+tilesFailed+" · 旧缓存 "+tilesStale
            +"\n源数据（含瓦片重叠）：线网要素 "+sourceRoads+" · 建筑面 "+sourceBuildings+" · 标注要素 "+sourceLabels
            +"\n本次处理图元 "+geometrySelected+"/"+geometryCandidates+" · 名称 "+labelsSelected+"/"+labelCandidates
            +"\n去重图元 "+geometryDuplicates+" · 去重标注 "+labelDuplicates+" · 超限对象 "+oversizedGroups
            +"\n未支持或无法转换的源要素 "+ignoredFeatures+"；下载完成不等于全部细节已发送";
    }
}
