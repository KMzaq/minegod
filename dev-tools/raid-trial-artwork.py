"""Regenerate the original sealed guardian textures and static geometry preview.

Run with Python, Pillow and NumPy installed. --repo-root overrides the repository
containing this script. The script never starts Minecraft or invokes Gradle.
The native Java model is the editable geometry source; the JSON is a diagnostic
cube/bone document, not a GeckoLib or Blockbench export. The static preview does
not exercise Minecraft rendering, networking or animation timing.
"""
from pathlib import Path
import argparse, ast, json, math, operator, random, re
import numpy as np
from PIL import Image, ImageDraw, ImageFont

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parent.parent)
args = parser.parse_args()
REPO_ROOT = args.repo_root.resolve()
ROOT = REPO_ROOT / "mythictrpg-main"
JAVA = ROOT / "src/main/java/com/sande/mythictrpg/client/raid/trial/SealedGuardianModel.java"
if not JAVA.is_file():
    parser.error("Repository does not contain the sealed guardian Java model: " + str(JAVA))
OUT = ROOT / "build/trial-guardian-preview"
PREVIEW = REPO_ROOT / "docs/assets/raid-trial/sealed_guardian_preview.png"
ASSETS = ROOT / "src/main/resources/assets/mythictrpg/textures/entity/trial"
OUT.mkdir(parents=True, exist_ok=True)
PREVIEW.parent.mkdir(parents=True, exist_ok=True)
ASSETS.mkdir(parents=True, exist_ok=True)
rng = random.Random(61013)
atlas = Image.new("RGBA", (256, 256))
for y in range(256):
    for x in range(256):
        grain = rng.randint(-10, 10)
        tile = ((x // 16) * 3 + (y // 16) * 7) % 9 - 4
        if x < 128 and y < 128:
            base = (109, 120, 120)
            shade = grain + tile * 2 + (8 if x % 16 == 0 or y % 16 == 0 else 0)
        elif x >= 128 and y < 128:
            base = (168, 133, 69)
            shade = grain + tile + (22 if x % 16 in (0, 1) else -12 if x % 16 == 15 else 0)
        elif x < 128:
            base = (42, 53, 59)
            shade = grain // 2 + tile
        else:
            base = (42, 211, 191)
            shade = grain + int(15 * math.sin(x * .28) * math.cos(y * .31))
        atlas.putpixel((x, y), tuple(max(0, min(255, c + shade)) for c in base) + (255,))
d = ImageDraw.Draw(atlas)
for _ in range(75):
    x, y = rng.randrange(2, 126), rng.randrange(2, 126)
    pts = [(x, y)]
    for _ in range(rng.randrange(3, 11)):
        x = max(1, min(126, x + rng.choice((-2, -1, 1, 2))))
        y = max(1, min(126, y + rng.choice((1, 1, 2))))
        pts.append((x, y))
    d.line(pts, fill=(56, 70, 72, 255), width=1)
for _ in range(90):
    x, y = rng.randrange(128), rng.randrange(128)
    d.rectangle((x, y, min(127, x + rng.randrange(1, 3)), min(127, y + 1)), fill=(80, 102, 87, 255))
for y in range(5, 124, 16):
    for x in range(132, 254, 16):
        d.rectangle((x, y, x + 7, y + 6), outline=(100, 83, 45, 255))
        d.line((x + 2, y + 1, x + 2, y + 5), fill=(219, 179, 101, 255))
        d.point((x + 5, y + 3), fill=(76, 133, 114, 255))
for y in range(136, 253, 12):
    for x in range(136, 253, 12):
        d.line((x, y + 5, x + 3, y + 5, x + 3, y, x + 7, y, x + 7, y + 8),
               fill=(130, 250, 220, 255), width=1)
atlas.save(ASSETS / "sealed_guardian.png")
glow = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
glow.paste(atlas.crop((128, 128, 256, 256)), (128, 128))
glow.save(ASSETS / "sealed_guardian_emissive.png")

source = JAVA.read_text(encoding="utf-8")
nodes = {"root": {"name": "root", "parent": None, "offset": [0, 0, 0], "rotation": [0, 0, 0], "cubes": []}}
variables = {"root": "root"}
pattern = re.compile(r'(\w+)\.addOrReplaceChild\("([^"\n]+)",\s*CubeListBuilder\.create\(\)(.*?),\s*PartPose\.(ZERO|offsetAndRotation|offset)(?:\((.*?)\))?\s*\);', re.S)
token = re.compile(r'\.texOffs\(([^)]*)\)|\.addBox\(([^)]*)\)')
def number(value, context):
    value = re.sub(r'(?<=\d)[fF]\b', '', value.strip())
    ternary = re.fullmatch(r'mirror\s*\?\s*(.*?)\s*:\s*(.*)', value)
    if ternary: value = ternary.group(1 if context['mirror'] else 2)
    names = {"PI": math.pi, **context}
    binary = {ast.Add: operator.add, ast.Sub: operator.sub,
              ast.Mult: operator.mul, ast.Div: operator.truediv}
    def evaluate(node):
        if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
            return node.value
        if isinstance(node, ast.Name) and node.id in names:
            return names[node.id]
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.UAdd, ast.USub)):
            result = evaluate(node.operand)
            return -result if isinstance(node.op, ast.USub) else result
        if isinstance(node, ast.BinOp) and type(node.op) in binary:
            return binary[type(node.op)](evaluate(node.left), evaluate(node.right))
        raise ValueError("Unsupported Java cube coordinate: " + value)
    return float(evaluate(ast.parse(value, mode="eval").body))
def values(raw, context):
    return [number(v, context) for v in raw.split(',')] if raw else []
def parse(text, context=None):
    context = context or {}
    for match in pattern.finditer(text):
        parentvar, name, builder, pose, raw = match.groups()
        parent = variables[parentvar]
        args = values(raw, context)
        offset = args[:3] if pose != "ZERO" else [0, 0, 0]
        rotation = args[3:] if pose == "offsetAndRotation" else [0, 0, 0]
        node = {"name": name, "parent": parent, "offset": offset, "rotation": rotation, "cubes": []}
        uv = [0, 0]
        for segment in token.finditer(builder):
            offs, cube = segment.groups()
            if offs:
                uv = values(offs, context)
            else:
                box = values(cube, context)
                assert len(box) == 6 and all(s > 0 for s in box[3:]), box
                w, h, depth = box[3:]
                assert uv[0] + 2 * (w + depth) <= 256 and uv[1] + depth + h <= 256, (uv, box)
                node["cubes"].append({"box": box, "uv": uv.copy()})
        node_key = parent + "." + name if name in nodes else name
        nodes[node_key] = node
        assignment = re.search(r'PartDefinition\s+(\w+)\s*=\s*$', text[max(0, match.start() - 70):match.start()])
        if assignment: variables[assignment.group(1)] = node_key

create = source.split("public static LayerDefinition createLayer()", 1)[1].split("return LayerDefinition.create", 1)[0]
parse(create)
for helper, params in (("arm", (("right_arm", -15, False), ("left_arm", 15, True))),
                       ("pauldron", (("right_pauldron", -16, False), ("left_pauldron", 16, True))),
                       ("leg", (("right_leg", -6, False), ("left_leg", 6, True)))):
    body = source.split("private static void " + helper + "(", 1)[1].split("\n    }", 1)[0]
    body = body.split("{", 1)[1]
    for name, x, mirror in params:
        parse(body.replace("addOrReplaceChild(name,", 'addOrReplaceChild("' + name + '",'), {"x": x, "mirror": mirror})
for index in range(8):
    angle = index * math.pi / 4
    nodes["fragment_" + str(index)] = {"name": "fragment_" + str(index), "parent": "seal",
        "offset": [math.sin(angle) * 16, math.cos(angle) * 16, 0], "rotation": [0, 0, -angle],
        "cubes": [{"box": [-4.2, -1.5, -1, 8.4, 3, 2], "uv": [128, 0]},
                  {"box": [-2.7, -.45, -1.4, 5.4, .9, .5], "uv": [128, 128]}]}

def matrix(node):
    x, y, z = node["rotation"]
    sx,cx,sy,cy,sz,cz=math.sin(x),math.cos(x),math.sin(y),math.cos(y),math.sin(z),math.cos(z)
    rx=np.array([[1,0,0],[0,cx,-sx],[0,sx,cx]])
    ry=np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]])
    rz=np.array([[cz,-sz,0],[sz,cz,0],[0,0,1]])
    m=np.eye(4);m[:3,:3]=rz@ry@rx;m[:3,3]=node["offset"]
    return (matrix(nodes[node["parent"]]) @ m) if node["parent"] else m

def render_panel(angle, origin, scale, canvas):
    yaw,elev=math.radians(angle),math.radians(12)
    cam=np.array([[math.cos(yaw),0,-math.sin(yaw)],
                  [math.sin(elev)*math.sin(yaw),math.cos(elev),math.sin(elev)*math.cos(yaw)],
                  [math.cos(elev)*math.sin(yaw),-math.sin(elev),math.cos(elev)*math.cos(yaw)]])
    faces=[]
    for node in nodes.values():
        m=matrix(node)
        for cube in node["cubes"]:
            x,y,z,w,h,dep=cube["box"];u,v=cube["uv"]
            verts=np.array([[x,y,z],[x+w,y,z],[x+w,y+h,z],[x,y+h,z],
                [x,y,z+dep],[x+w,y,z+dep],[x+w,y+h,z+dep],[x,y+h,z+dep]])
            verts=(np.c_[verts,np.ones(8)] @ m.T)[:,:3] @ cam.T
            specs=[([0,1,2,3],(u+dep,v+dep,w,h),.96),([5,4,7,6],(u+dep+w+dep,v+dep,w,h),.67),
                   ([4,0,3,7],(u,v+dep,dep,h),.77),([1,5,6,2],(u+dep+w,v+dep,dep,h),.84),
                   ([4,5,1,0],(u+dep,v,w,dep),1.06),([3,2,6,7],(u+dep+w,v,w,dep),.64)]
            for ids,uv,shade in specs:
                pts=verts[ids];p2=pts[:,:2]*scale + np.array(origin)
                faces.append((pts[:,2].mean(),p2,uv,shade))
    for _,p2,(u,v,w,h),shade in sorted(faces,key=lambda f:-f[0]):
        edge1, edge2 = p2[1]-p2[0], p2[2]-p2[0]
        if abs(edge1[0]*edge2[1]-edge1[1]*edge2[0])<.2:continue
        arr=[];rhs=[]
        rect=[(u,v),(u+w,v),(u+w,v+h),(u,v+h)]
        for (xx,yy),(uu,vv) in zip(p2,rect):
            arr.extend([[xx,yy,1,0,0,0,-uu*xx,-uu*yy],[0,0,0,xx,yy,1,-vv*xx,-vv*yy]])
            rhs.extend([uu,vv])
        try: coeff=np.linalg.solve(np.array(arr),np.array(rhs))
        except np.linalg.LinAlgError:continue
        image=atlas.transform(canvas.size,Image.Transform.PERSPECTIVE,coeff,Image.Resampling.NEAREST)
        mask=Image.new("L",canvas.size);ImageDraw.Draw(mask).polygon([tuple(p) for p in p2],fill=255)
        rgb=np.array(image)
        rgb[:,:,:3]=np.clip(rgb[:,:,:3].astype(float)*shade,0,255).astype(np.uint8)
        canvas.paste(Image.fromarray(rgb), (0,0), mask)

preview=Image.new("RGBA",(1400,1000),(18,25,29,255));p=ImageDraw.Draw(preview)
for y in range(1000):
    p.line((0,y,1400,y), fill=(18+int(y*.006),25+int(y*.009),29+int(y*.012),255))
p.ellipse((170,785,795,900),fill=(13,19,24,255))
p.ellipse((845,710,1325,790),fill=(13,19,24,255))
render_panel(-27,(480,525),10.8,preview)
render_panel(145,(1095,490),7.8,preview)
p=ImageDraw.Draw(preview)
font_candidates = [Path("C:/Windows/Fonts/arial.ttf"),
                   Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")]
font_path = next((p for p in font_candidates if p.is_file()), None)
large = ImageFont.truetype(str(font_path),34) if font_path else ImageFont.load_default(size=34)
small = ImageFont.truetype(str(font_path),19) if font_path else ImageFont.load_default(size=19)
p.text((56,42),"SEALED STONE GUARDIAN",font=large,fill=(223,207,164,255))
p.text((58,87),"Original native model / teal seal core / weathered stone + aged bronze",font=small,fill=(133,166,166,255))
p.text((58,937),"Static geometry preview. Minecraft renderer, client animation and combat timing require in-game verification.",font=small,fill=(153,174,177,255))
preview.convert("RGB").save(PREVIEW)
# Source manifest for the preview is an explicit cube/bone document, not a claimed loader format.
(OUT/"sealed_guardian_geometry.json").write_text(json.dumps({"source":str(JAVA),"textureSize":[256,256],"nodes":nodes},indent=2),encoding="utf-8")
print(json.dumps({"parts":len(nodes),"cubes":sum(len(n["cubes"]) for n in nodes.values()),
    "atlas":str(ASSETS/"sealed_guardian.png"),"preview":str(PREVIEW)}))
