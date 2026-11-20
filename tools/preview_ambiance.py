# Desktop simulation of the Ambiance home screen (python3 tools/preview_ambiance.py, needs Pillow), from the values in
# HomeView (SCENE_AT, the glass row, the corner buttons). Update it with every change to the screen: it renders the
# pictures the design canvas shows as "Écran actuel".
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance
import sys
import os
A=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "assets") + "/"; P=A+"scenes/"
font=A+"fonts/Antonio-Light.ttf"; inter=A+"fonts/InterVariable.ttf"
# name: date top, clock top, size, left(-1 centre), dark ink, fill, cut-out  (same columns as SCENE_AT)
SC={"night":(70,120,330,-1,0,3,0),"dawn":(70,120,330,-1,0,0,0),"day":(70,120,330,-1,0,0,0),"sunset":(70,120,330,-1,0,0,0)}
APPS=[("ATLAS PRO",(20,20,24)),("CANAL+",(0,0,0)),("TF1+",(29,59,209)),("prime video",(26,152,255)),("Apple tv",(0,0,0)),("YouTube",(245,245,247))]
SERVICES={"CANAL+":"CANAL+","TF1+":"TF1+","prime video":"Prime Video","Apple tv":"Apple TV"}
def render(name, out, picks=False, focus=1, t="20:47", date="Vendredi 20 novembre", wx="☁ 8° Couvert"):
    dt,ct,size,left,dark,fillm,cut=SC[name]; W,H=1920,1080
    im=Image.open(P+f"{name}.jpg").convert("RGBA")
    glass=Image.open(P+f"{name}-glass.jpg").convert("RGBA").resize((W,H))
    if fillm==1: glass=ImageEnhance.Brightness(glass).enhance(1.1)
    if fillm==3: glass=Image.eval(glass.convert('RGB'),lambda v:int(v*0xB4/255+0x4E)).convert('RGBA')   # clockLight
    # top shade (0x40 black → 0 over 58%)
    sh=Image.new("L",(1,H)); 
    for y in range(H): sh.putpixel((0,y),int(0x40*max(0,1-y/(H*0.58))))
    im=Image.composite(Image.new("RGBA",(W,H),(0,0,0,255)),im,sh.resize((W,H)))
    f=ImageFont.truetype(font,int(size*0.82)); d=ImageDraw.Draw(im)
    tw=d.textlength(t,font=f); x= left if left>=0 else (W-tw)/2; base=ct+0.76*size
    mask=Image.new("L",(W,H),0); ImageDraw.Draw(mask).text((x,base),t,font=f,fill=255,anchor="ls")
    s2=mask.filter(ImageFilter.GaussianBlur(28)).point(lambda v:int(v*0.16))
    im=Image.composite(Image.new("RGBA",(W,H),(0,0,0,255)),im,s2)
    im=Image.composite(glass,im,mask.point(lambda v:int(v*(0.9 if fillm==1 else 1))))
    rim=Image.new("L",(W,H),0); ImageDraw.Draw(rim).text((x,base),t,font=f,fill=0,anchor="ls",stroke_width=1,stroke_fill=140)
    im=Image.composite(Image.new("RGBA",(W,H),(255,255,255,255)),im,rim)
    d=ImageDraw.Draw(im); fd=ImageFont.truetype(inter,34)
    full=date+"     "+wx; dw=d.textlength(full,font=fd); dx= left if left>=0 else (W-dw)/2
    d.text((dx,dt+30),full,font=fd,fill=(22,32,29) if dark else (255,255,255),anchor="ls")
    # corner buttons on the Fire TV (sleep, tonight, the LED strip once tuya_* is set, settings): 68 px circles,
    # centre y 103, right edge 1840; tonight and a lit strip in orange (HomeView.drawLightButtons)
    for i,(sym,on) in enumerate([("settings",0),("lightbulb",1),("sparkles",picks),("bedtime",0)]):
        cx=1840-34-i*92; d.ellipse((cx-34,69,cx+34,137),fill=(30,30,36,150),outline=(255,255,255,60))
        ic=Image.open(A+"symbols/"+sym+".png").convert("RGBA"); ic=ic.resize((30,int(30*ic.height/ic.width)))
        tint=Image.new("RGBA",ic.size,(255,159,10,255) if on else (235,235,235,255)); tint.putalpha(ic.getchannel("A"))
        im.alpha_composite(tint,(int(cx-ic.width/2),int(103-ic.height/2))); d=ImageDraw.Draw(im)
    # the glass row: platter top 814, tiles 126 high, gap 48, pad 40, max 270 wide
    n=len(APPS); full_w=1760; tw2=min(270,(full_w-(n-1)*48)/n); width=n*tw2+(n-1)*48; L=(W-width)/2
    panel=(int(L-40),814,int(L+width+40),814+126+80)
    blur=im.crop(panel).filter(ImageFilter.GaussianBlur(28)); blur.alpha_composite(Image.new("RGBA",blur.size,(30,20,28,122)))
    m=Image.new("L",blur.size,0); ImageDraw.Draw(m).rounded_rectangle((0,0,blur.size[0]-1,blur.size[1]-1),radius=62,fill=255)
    im.paste(blur,panel[:2],m); d=ImageDraw.Draw(im)
    d.rounded_rectangle(panel,radius=62,outline=(255,210,220,70),width=1)
    fl=ImageFont.truetype(inter,26)
    for i,(lab,col) in enumerate(APPS):
        x0=L+i*(tw2+48); foc=i==focus; s=1.08 if foc else 1
        cx,cy=x0+tw2/2,854+63-(10 if foc else 0); hw,hh=tw2*s/2,126*s/2
        box=(cx-hw,cy-hh,cx+hw,cy+hh)
        c=col if foc else tuple(int(v*0.82) for v in col)
        d.rounded_rectangle(box,radius=22,fill=c); d.text((cx,cy),lab,font=fl,fill=(17,17,17) if sum(col)>600 else (255,255,255),anchor="mm")
        if foc:
            d.rounded_rectangle(box,radius=22,outline=(255,255,255),width=4)
            d.text((cx,box[3]+40),lab,font=ImageFont.truetype(inter,21),fill=(255,255,255),anchor="ms")
    if picks:
        cw,chh,gap=360,203,40; Lc=(W-(3*cw+2*gap))/2; bottom=814-48
        # "Tonight" follows the focused app when it's a streaming service (Recs.forApp), else the mix of them all
        svc=SERVICES.get(APPS[focus][0]); metas=[svc]*3 if svc else ["CANAL+","Prime Video","TF1+"]
        d.text((W/2,bottom-chh-22),"CE SOIR SUR "+svc.upper() if svc else "CE SOIR",font=ImageFont.truetype(inter,20),fill=(240,240,240),anchor="ms")
        for i in range(3):
            x0=Lc+i*(cw+gap); d.rounded_rectangle((x0,bottom-chh,x0+cw,bottom),radius=18,fill=(40,40,52),outline=(255,255,255,40))
            d.text((x0+18,bottom-60),"[Affiche TMDB]",font=ImageFont.truetype(inter,18),fill=(200,200,200),anchor="ls")
            d.text((x0+18,bottom-18),"[Titre] · ★ 7,4 · "+metas[i],font=ImageFont.truetype(inter,18),fill=(230,230,230),anchor="ls")
    else:
        ft=ImageFont.truetype(inter,26); fq=ImageFont.truetype(inter,23)
        d.text((W/2,814-34-40),"Nour a 9 mois et 14 jours",font=ft,fill=(255,255,255),anchor="ms")
        d.text((W/2,814-34),"« La simplicité est la sophistication suprême. »  — Léonard de Vinci",font=fq,fill=(240,240,240),anchor="ms")
    im.convert("RGB").save(out,quality=88)
# The LED strip's panel (OK held on the bulb), same values as HomeView.drawLightsPanel: LP_W 1040, LP_H 820, pad 44
SWATCHES=[(255,201,138),(243,241,234),(47,75,216),(139,63,217),(229,72,77),(47,179,107)]
MOODS=["Cinéma","Tamisé","Soirée","Lecture"]
def render_lights(name, out, row=2, on=True, bright=70, swatch=0, mood=1, focus_swatch=2, focus_mood=0, auto_on=True, auto_off=True):
    render(name, out)
    im=Image.open(out).convert("RGBA"); W,H=im.size
    im.alpha_composite(Image.new("RGBA",(W,H),(0,0,0,0x99)))
    d=ImageDraw.Draw(im); F=lambda w,s: ImageFont.truetype(inter,s)
    def rr(box,radius,fill):   # a see-through fill blends, as on the TV (ImageDraw alone would overwrite)
        layer=Image.new("RGBA",im.size,(0,0,0,0)); ImageDraw.Draw(layer).rounded_rectangle(box,radius=radius,fill=fill)
        im.alpha_composite(layer)
    L,T=(W-1040)/2,(H-820)/2; R=L+1040-44; x=L+44
    rr((L,T,L+1040,T+820),36,(22,26,34,250)); d=ImageDraw.Draw(im)
    d.rounded_rectangle((L,T,L+1040,T+820),radius=36,outline=(70,72,78),width=2)
    d.text((x,T+80),"Ruban LED",font=F(600,36),fill=(245,243,238),anchor="ls")
    d.text((x,T+116),"Derrière la TV",font=F(400,22),fill=(163,166,174),anchor="ls")
    sw=(R-132,T+50,R,T+120); rr(sw,35,(255,181,71) if on else (245,243,238,51)); d=ImageDraw.Draw(im)
    kx=sw[2]-35 if on else sw[0]+35; d.ellipse((kx-28,T+85-28,kx+28,T+85+28),fill=(11,13,18) if on else (245,243,238))
    if row==0: d.rounded_rectangle((sw[0]-9,sw[1]-9,sw[2]+9,sw[3]+9),radius=44,outline=(245,243,238),width=4)
    y=T+190; lab=lambda r:(245,243,238) if row==r else (201,203,209)
    d.text((x,y),"Luminosité",font=F(500,22),fill=lab(1),anchor="ls"); d.text((R,y),f"{bright} %",font=F(500,22),fill=(201,203,209),anchor="rs")
    by=y+30; rr((x,by,R,by+18),9,(245,243,238,31)); d=ImageDraw.Draw(im)
    kn=x+(R-x)*bright/100
    grad=Image.new("RGBA",(int(kn-x),18)); gd=ImageDraw.Draw(grad)
    for i in range(grad.width):
        t=i/max(1,(R-x)); gd.line((i,0,i,17),fill=(int(138+(255-138)*t),int(90+(181-90)*t),int(30+(71-30)*t)))
    m=Image.new("L",grad.size,0); ImageDraw.Draw(m).rounded_rectangle((0,0,grad.width-1,17),radius=9,fill=255)
    im.paste(grad,(int(x),int(by)),m); d=ImageDraw.Draw(im)
    d.ellipse((kn-18,by+9-18,kn+18,by+9+18),fill=(245,243,238))
    if row==1:
        d.ellipse((kn-26,by+9-26,kn+26,by+9+26),outline=(245,243,238),width=4); d.text((x,by+58),"← → pour régler",font=F(400,18),fill=(163,166,174),anchor="ls")
    y=T+340; d.text((x,y),"Couleur",font=F(500,22),fill=lab(2),anchor="ls")
    names=["Blanc chaud","Blanc","Bleu nuit","Violet","Rouge","Vert"]
    if row==2: d.text((R,y),names[focus_swatch],font=F(500,22),fill=(201,203,209),anchor="rs")
    for i,col in enumerate(SWATCHES):
        cx=x+36+i*90; cy=y+60; d.ellipse((cx-36,cy-36,cx+36,cy+36),fill=col)
        if i==swatch and on: d.ellipse((cx-30,cy-30,cx+30,cy+30),outline=(11,13,18),width=3)
        if row==2 and i==focus_swatch: d.ellipse((cx-45,cy-45,cx+45,cy+45),outline=(245,243,238),width=4)
    y=T+500; d.text((x,y),"Ambiances",font=F(500,22),fill=lab(3),anchor="ls")
    px=x; top=y+22
    for i,n in enumerate(MOODS):
        cur=on and i==mood; f=F(600 if cur else 500,22); pw=d.textlength(n,font=f)+56
        rr((px,top,px+pw,top+64),16,(245,243,238) if cur else (245,243,238,20)); d=ImageDraw.Draw(im)
        d.text((px+pw/2,top+32+8),n,font=f,fill=(11,13,18) if cur else (245,243,238),anchor="ms")
        if row==3 and i==focus_mood: d.rounded_rectangle((px-7,top-7,px+pw+7,top+71),radius=22,outline=(245,243,238),width=4)
        px+=pw+14
    rr((x,T+630,R,T+631.5),0,(245,243,238,26)); d=ImageDraw.Draw(im)
    for r,y,label,hint,val in [(4,T+690,"S'allume le soir avec la TV","Après le coucher du soleil, 30 min avant s'il pleut",auto_on),
                               (5,T+770,"S'éteint avec la TV",None,auto_off)]:
        d.text((x,y),label,font=F(500,22),fill=lab(r),anchor="ls")
        if hint: d.text((x,y+28),hint,font=F(400,18),fill=(163,166,174),anchor="ls")
        cy=y-8+(14 if hint else 0); box=(R-88,cy-24,R,cy+24)
        rr(box,24,(255,181,71) if val else (245,243,238,51)); d=ImageDraw.Draw(im)
        kx=box[2]-24 if val else box[0]+24; d.ellipse((kx-18,cy-18,kx+18,cy+18),fill=(11,13,18) if val else (245,243,238))
        if row==r: d.rounded_rectangle((box[0]-7,box[1]-7,box[2]+7,box[3]+7),radius=31,outline=(245,243,238),width=4)
    im.convert("RGB").save(out,quality=88)
if __name__=="__main__":
    for n in SC: render(n, f"ecran-{n}.jpg")
    render("day","ecran-day-cesoir.jpg",picks=True)                 # Canal+ focused: tonight on Canal+
    render("day","ecran-day-cesoir-mix.jpg",picks=True,focus=5)     # YouTube focused: the mix
    render_lights("night","ecran-ruban.jpg")                        # OK held on the bulb: the LED strip's panel
