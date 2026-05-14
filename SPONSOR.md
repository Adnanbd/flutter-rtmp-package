New Sponsor Chnages: 
remove x and y - 
new param: 
left:  LEFT-edge position 
right: RIGHT-edge position 
top:   TOP-edge position 
bottom:BOTTOM-edge position

(If left and right both provided, then auto anchor-proportional horizontally center)
(If top and bottm both provided, then auto anchor-proportional vertically center)

width : 1 -100
height: 1 -100

it follow given width, then trying to auto aspect height, if auto aspect height exceed given height, then it follow given height, then trying to auto aspect width. (Similar of Flutters BoxFit.contain)

List, set at configure / replaced via updateSponsors (Same as before)

