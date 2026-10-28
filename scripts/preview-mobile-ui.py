"""Code-based illustrative preview; deliberately labelled, never a device screenshot."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

S = 2
INK = '#1c2d45'
MUTED = '#65758b'
BLUE = '#315ee9'
BG = '#f3f6fb'
BORDER = '#e5ebf3'
SOFT = '#edf2fd'
WHITE = '#ffffff'
OUT = Path(__file__).resolve().parents[1] / 'release' / 'preview-v0.4.7'
OUT.mkdir(parents=True, exist_ok=True)


def font(n, bold=False):
    return ImageFont.truetype(str(Path('C:/Windows/Fonts') / ('msyhbd.ttc' if bold else 'msyh.ttc')), round(n * S))


def box(im, xy, color, radius=0, border=None):
    ImageDraw.Draw(im).rounded_rectangle(tuple(round(v*S) for v in xy), radius=radius*S, fill=color, outline=border, width=S)


def text(im, xy, value, size=14, color=INK, bold=False):
    ImageDraw.Draw(im).text(tuple(round(v*S) for v in xy), value, font=font(size,bold), fill=color)


def para(im, xy, value, width, size=13, color=MUTED, line=21):
    x,y=xy; row=''
    for ch in value:
        if ch=='\n' or ImageDraw.Draw(im).textlength(row+ch,font=font(size))>width*S:
            text(im,(x,y),row,size,color);y+=line;row=''
        if ch!='\n':row+=ch
    text(im,(x,y),row,size,color)
    return y+line


def button(im, xy, value, primary=False, size=13):
    x,y,w,h=xy;box(im,(x,y,x+w,y+h),BLUE if primary else SOFT,10)
    f=font(size,True);d=ImageDraw.Draw(im);tw=d.textlength(value,font=f)/S
    text(im,(x+(w-tw)/2,y+(h-size)/2-3),value,size,WHITE if primary else BLUE,True)


def field(im, x,y,w,label,value,focused=False):
    text(im,(x,y),label,12,MUTED)
    box(im,(x,y+26,x+w,y+76),WHITE if focused else BG,12,BLUE if focused else BORDER)
    text(im,(x+14,y+40),value,14)


def base(selected=0):
    im=Image.new('RGB',(390*S,844*S),BG)
    box(im,(0,0,390,164),WHITE)
    text(im,(21,11),'21:45',12,INK,True);text(im,(287,11),'5G',11);text(im,(335,11),'82%',11)
    box(im,(313,16,328,24),WHITE,2,INK);box(im,(315,18,324,22),INK,1)
    text(im,(20,49),'OfferIN Local',22,INK,True)
    box(im,(266,43,370,87),SOFT,12);text(im,(284,57),'模型与余额',11,BLUE)
    box(im,(20,101,370,145),BG,12);text(im,(31,114),'● 已连接电脑 · 点击管理连接',12,MUTED)
    box(im,(0,762,390,844),WHITE)
    for i,label in enumerate(['笔试','记录','设置']):
        x=16+i*122
        if i==selected:box(im,(x,771,x+114,819),'#eaf0ff',12)
        text(im,(x+43,786),label,13,BLUE if i==selected else MUTED,True)
    box(im,(143,835,247,839),'#c5ccd7',2)
    return im


def work():
    im=base();text(im,(16,185),'笔试工作台',23,INK,True)
    text(im,(16,224),'选择记录，接收题目，继续你的解答。',12,MUTED)
    box(im,(16,261,374,411),WHITE,18,BORDER)
    text(im,(32,279),'算法练习 · 已保存 2 道题',16,INK,True)
    for x,label,main in [(32,'新建笔试',True),(143,'继续记录',False),(254,'结束保存',False)]:button(im,(x,317,104,48),label,main)
    text(im,(32,379),'题图、解答和追问自动保存在当前记录中。',11,MUTED)
    box(im,(16,425,374,747),WHITE,18,BORDER)
    text(im,(32,444),'当前题目',16,INK,True)
    box(im,(32,481,358,691),BG,12,BORDER)
    box(im,(47,506,343,666),WHITE,3,'#e2e7ef')
    text(im,(61,520),'示例题图',10,MUTED)
    text(im,(61,548),'1. 求数列前 n 项的和',13,INK,True)
    text(im,(61,579),'S(n) = 1 + 2 + 3 + … + n',15)
    text(im,(61,614),'请给出推导过程与结论。',11,MUTED)
    text(im,(32,707),'已收到图片 · 1920 × 1200 · 168 KB',11,MUTED)
    return im


def modal():
    im=work().convert('RGBA')
    im=Image.alpha_composite(im,Image.new('RGBA',im.size,(0,0,0,82))).convert('RGB')
    # Mirrors MainActivity.newExamDialog, with an active record notice.
    x,y,w,h=24,220,342,404
    box(im,(x,y,x+w,y+h),WHITE,24)
    box(im,(48,244,94,290),SOFT,14);text(im,(57,247),'＋',25,BLUE)
    text(im,(108,251),'新建笔试记录',21,INK,True)
    para(im,(48,307),'给这次练习起个名字，方便之后继续和回看。',294,13,line=21)
    field(im,48,370,294,'记录名称','默认记录')
    text(im,(48,456),'题图、解答和追问会自动保存在这份记录中。',11,MUTED)
    box(im,(48,490,342,544),SOFT,10)
    para(im,(60,502),'当前笔试会先结束并保存，再开始新的记录。',270,12,BLUE,line=19)
    button(im,(48,568,143,48),'取消');button(im,(199,568,143,48),'开始笔试',True)
    return im


def settings():
    im=base(2);text(im,(16,185),'设置',23,INK,True);text(im,(16,224),'连接、模型与使用偏好。',12,MUTED)
    box(im,(16,261,374,442),WHITE,18,BORDER);text(im,(32,280),'双端连接',18,INK,True)
    para(im,(32,316),'启动电脑连接服务，然后扫描电脑二维码。也可以粘贴电脑生成的配对链接。',326,12,line=21)
    button(im,(32,378,159,48),'扫描电脑二维码',True);button(im,(199,378,159,48),'断开并忘记电脑')
    box(im,(16,456,374,622),WHITE,18,BORDER);text(im,(32,483),'平台可用余额',16,INK,True)
    button(im,(199,473,159,48),'刷新');text(im,(32,530),'—',30,BLUE)
    text(im,(32,586),'读取平台账户余额，不按本机调用估算。',12,MUTED)
    box(im,(16,636,374,946),WHITE,18,BORDER);text(im,(32,656),'视觉模型服务',18,INK,True)
    field(im,32,696,326,'API 服务地址','https://api.deepseek.com')
    # App bottom navigation overlays the scroll viewport.
    nav=base(2);im.paste(nav.crop((0,762*S,390*S,844*S)),(0,762*S))
    return im


home,dialog,config=work(),modal(),settings()
for name,im in [('work',home),('new-record',dialog),('settings',config)]:im.save(OUT/(name+'.png'))
canvas=Image.new('RGB',(872*S,1030*S),'#eaf0f7')
text(canvas,(32,26),'OfferIN Local',26,INK,True)
text(canvas,(32,68),'v0.4.7 · 按当前代码绘制的界面示意，非手机实拍',13,MUTED)
text(canvas,(32,113),'工作台',16,INK,True);text(canvas,(450,113),'新建记录弹窗',16,INK,True)
canvas.paste(home,(32*S,150*S));canvas.paste(dialog,(450*S,150*S))
text(canvas,(32,1007),'示例连接状态、记录名称和题图用于展示布局。',11,MUTED)
canvas.save(OUT/'app-preview.png')
print(OUT/'app-preview.png')
