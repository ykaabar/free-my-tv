# Desktop simulation of the Ambiance home screen (python3 tools/preview_ambiance.py, needs Pillow), from the values in
# HomeView (SCENE_AT, the glass row, the corner buttons). Update it with every change to the screen: it renders the
# pictures the design canvas shows as "Écran actuel".
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance
import sys
import os
A=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "assets") + "/"; P=A+"scenes/"
font=A+"fonts/Antonio-Light.ttf"; inter=A+"fonts/InterVariable.ttf"
# name: date top, clock top, size, left(-1 centre), dark ink, fill, cut-out  (same columns as SCENE_AT)
SC={"night":(96,150,400,-1,0,3,0),"dawn":(200,250,340,1020,0,0,0),"day":(70,120,330,-1,0,0,0)}
APPS=[("ATLAS PRO",(20,20,24)),("CANAL+",(0,0,0)),("TF1+",(29,59,209)),("prime video",(26,152,255)),("Apple tv",(0,0,0)),("YouTube",(245,245,247))]
def render(name, out, picks=False, t="20:47", date="Vendredi 20 novembre", wx="☁ 8° Couvert"):
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
    # corner buttons (sleep, tonight, settings): 68 px circles, centre y 103, right edge 1840
    for i in range(3):
        cx=1840-34-i*92; d.ellipse((cx-34,69,cx+34,137),fill=(30,30,36,150),outline=(255,255,255,60))
    # the glass row: platter top 814, tiles 126 high, gap 48, pad 40, max 270 wide
    n=len(APPS); full_w=1760; tw2=min(270,(full_w-(n-1)*48)/n); width=n*tw2+(n-1)*48; L=(W-width)/2
    panel=(int(L-40),814,int(L+width+40),814+126+80)
    blur=im.crop(panel).filter(ImageFilter.GaussianBlur(28)); blur.alpha_composite(Image.new("RGBA",blur.size,(30,20,28,122)))
    m=Image.new("L",blur.size,0); ImageDraw.Draw(m).rounded_rectangle((0,0,blur.size[0]-1,blur.size[1]-1),radius=62,fill=255)
    im.paste(blur,panel[:2],m); d=ImageDraw.Draw(im)
    d.rounded_rectangle(panel,radius=62,outline=(255,210,220,70),width=1)
    fl=ImageFont.truetype(inter,26)
    for i,(lab,col) in enumerate(APPS):
        x0=L+i*(tw2+48); foc=i==1; s=1.08 if foc else 1
        cx,cy=x0+tw2/2,854+63-(10 if foc else 0); hw,hh=tw2*s/2,126*s/2
        box=(cx-hw,cy-hh,cx+hw,cy+hh)
        c=col if foc else tuple(int(v*0.82) for v in col)
        d.rounded_rectangle(box,radius=22,fill=c); d.text((cx,cy),lab,font=fl,fill=(17,17,17) if sum(col)>600 else (255,255,255),anchor="mm")
        if foc:
            d.rounded_rectangle(box,radius=22,outline=(255,255,255),width=4)
            d.text((cx,box[3]+40),"CANAL+",font=ImageFont.truetype(inter,21),fill=(255,255,255),anchor="ms")
    if picks:
        cw,chh,gap=360,203,40; Lc=(W-(3*cw+2*gap))/2; bottom=814-48
        d.text((W/2,bottom-chh-22),"CE SOIR",font=ImageFont.truetype(inter,20),fill=(240,240,240),anchor="ms")
        for i in range(3):
            x0=Lc+i*(cw+gap); d.rounded_rectangle((x0,bottom-chh,x0+cw,bottom),radius=18,fill=(40,40,52),outline=(255,255,255,40))
            d.text((x0+18,bottom-60),"[Affiche TMDB]",font=ImageFont.truetype(inter,18),fill=(200,200,200),anchor="ls")
            d.text((x0+18,bottom-18),"[Titre] · ★ 7,4 · CANAL+",font=ImageFont.truetype(inter,18),fill=(230,230,230),anchor="ls")
    else:
        ft=ImageFont.truetype(inter,26); fq=ImageFont.truetype(inter,23)
        d.text((W/2,814-34-40),"Nour a 9 mois et 14 jours",font=ft,fill=(255,255,255),anchor="ms")
        d.text((W/2,814-34),"« La simplicité est la sophistication suprême. »  — Léonard de Vinci",font=fq,fill=(240,240,240),anchor="ms")
    im.convert("RGB").save(out,quality=88)
if __name__=="__main__":
    for n in SC: render(n, f"ecran-{n}.jpg")
    render("day","ecran-day-cesoir.jpg",picks=True)
