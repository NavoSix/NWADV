import sys, math
from route import plan, gpx_tracks, dist
out=sys.argv[1]
wp=[(43.8720,-71.5850),(43.8600,-71.5520),(43.8400,-71.5200)]
p=plan(wp,14,0.16,pad=0.01)
L=sum(dist(a,b) for a,b in zip(p,p[1:]))
grades=[]
for i in range(0,len(p)-6,3):
    d=sum(dist(p[j],p[j+1]) for j in range(i,i+6))
    grades.append(abs(p[i+6][2]-p[i][2])/max(d,1))
print("c6 points",len(p),"km",round(L/1000,2),"max grade %.0f%%"%(100*max(grades)),"ele",round(min(q[2] for q in p)),round(max(q[2] for q in p)))
open(out+"/classvi_practice.gpx","w").write(gpx_tracks("Class VI practice trail",
  "A generated practice trail over real terrain near Sandwich Notch (not a real road). Ruts, rocks, water bars, ledge and mud holes are generated along it.",
  [("Practice trail","classvi",p)]))
