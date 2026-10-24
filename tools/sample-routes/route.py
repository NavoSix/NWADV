import sys, math, numpy as np
from skimage.graph import route_through_array
from dem import mosaic, px, latlon
def plan(waypoints, z, grade_ok, pad=0.03):
    lats=[p[0] for p in waypoints]; lons=[p[1] for p in waypoints]
    m, ox, oy = mosaic(min(lats)-pad, min(lons)-pad, max(lats)+pad, max(lons)+pad, z)
    lat_c=sum(lats)/len(lats)
    mpp = 2*math.pi*6378137*math.cos(math.radians(lat_c))/(256*2**z)
    gy, gx = np.gradient(m, mpp)
    slope = np.hypot(gx, gy)
    cost = 1 + (slope/grade_ok)**4
    cost = np.minimum(cost, 5000)
    path=[]
    for a,b in zip(waypoints, waypoints[1:]):
        xa,ya=px(*a,z); xb,yb=px(*b,z)
        idx,_=route_through_array(cost,(int(ya-oy),int(xa-ox)),(int(yb-oy),int(xb-ox)),fully_connected=True,geometric=True)
        path += idx if not path else idx[1:]
    pts=[]
    for (r,c) in path:
        lat,lon=latlon(c+ox+0.5, r+oy+0.5, z)
        pts.append((lat,lon,float(m[r,c])))
    # smooth with moving average (roads don't zig-zag on pixel grid)
    k=4
    sm=[]
    for i in range(len(pts)):
        a=max(0,i-k); b=min(len(pts),i+k+1)
        sm.append((sum(p[0] for p in pts[a:b])/(b-a), sum(p[1] for p in pts[a:b])/(b-a), pts[i][2]))
    sm=sm[::2]
    return sm
def dist(a,b):
    return math.hypot((a[0]-b[0])*111132, (a[1]-b[1])*111320*math.cos(math.radians(a[0])))
def gpx_tracks(name, desc, tracks):
    out=['<?xml version="1.0" encoding="UTF-8"?>',
         '<gpx version="1.1" creator="NWADV sample generator" xmlns="http://www.topografix.com/GPX/1/1">',
         f'  <metadata><name>{name}</name><desc>{desc}</desc></metadata>']
    for tname, ttype, pts in tracks:
        out.append(f'  <trk><name>{tname}</name><type>{ttype}</type><trkseg>')
        for la,lo,e in pts: out.append(f'    <trkpt lat="{la:.6f}" lon="{lo:.6f}"><ele>{e:.1f}</ele></trkpt>')
        out.append('  </trkseg></trk>')
    out.append('</gpx>')
    return "\n".join(out)+"\n"
