import sys
from route import plan, gpx_tracks, dist
out=sys.argv[1]
kanc=[(44.0453,-71.6704),(44.0638,-71.5880),(44.0480,-71.5400),(43.9970,-71.3960),(44.0035,-71.3390),(44.0090,-71.2720),(43.9800,-71.1197)]
p=plan(kanc,13,0.07)
L=sum(dist(a,b) for a,b in zip(p,p[1:]))
hi=max(p,key=lambda q:q[2])
print("kanc points",len(p),"length km",round(L/1000,1),"high point",hi)
open(out+"/kancamagus_approx.gpx","w").write(gpx_tracks("Kancamagus Highway (approximate)",
  "Sample ride: Lincoln to Conway over Kancamagus Pass. The track was generated from terrain data and only approximates NH-112; with an internet connection the real roads come from OpenStreetMap.",
  [("Kancamagus Highway","paved",p)]))
