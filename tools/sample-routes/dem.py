import math, os, io, urllib.request, numpy as np
from PIL import Image
D=os.path.join(os.path.dirname(os.path.abspath(__file__)),'.dem-cache'); os.makedirs(D, exist_ok=True)
def tile(z,x,y):
    p=os.path.join(D,f"{z}_{x}_{y}.png")
    if not os.path.exists(p):
        data=urllib.request.urlopen(f"https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png",timeout=30).read()
        open(p,'wb').write(data)
    a=np.asarray(Image.open(p).convert('RGB')).astype(np.float64)
    return a[:,:,0]*256+a[:,:,1]+a[:,:,2]/256-32768
def px(lat,lon,z):
    n=256*2**z
    x=(lon+180)/360*n
    r=math.radians(lat)
    y=(1-math.log(math.tan(r)+1/math.cos(r))/math.pi)/2*n
    return x,y
def latlon(x,y,z):
    n=256*2**z
    lon=x/n*360-180
    lat=math.degrees(math.atan(math.sinh(math.pi*(1-2*y/n))))
    return lat,lon
def mosaic(lat0,lon0,lat1,lon1,z):
    x0,y0=px(lat1,lon0,z); x1,y1=px(lat0,lon1,z)
    tx0,ty0,tx1,ty1=int(x0//256),int(y0//256),int(x1//256),int(y1//256)
    rows=[]
    for ty in range(ty0,ty1+1):
        rows.append(np.hstack([tile(z,tx,ty) for tx in range(tx0,tx1+1)]))
    return np.vstack(rows), tx0*256, ty0*256
def ele(lat,lon,z=13):
    x,y=px(lat,lon,z); t=tile(z,int(x//256),int(y//256)); return t[int(y)%256,int(x)%256]
