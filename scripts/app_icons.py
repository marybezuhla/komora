"""Android VectorDrawable / adaptive icon -> SVG, enough for launcher icons."""
import re, sys, os
import xml.etree.ElementTree as ET
A = '{http://schemas.android.com/apk/res/android}'
AAPT = '{http://schemas.android.com/aapt}'

def colors(res):
    out = {}
    for d in ('values', 'values-night'):
        if d != 'values': continue
        p = os.path.join(res, d, 'colors.xml')
        if os.path.exists(p):
            for c in ET.parse(p).getroot().iter('color'):
                out[c.get('name')] = (c.text or '').strip()
    return out

def col(v, cmap, depth=0):
    if v is None: return None, 1.0
    v = v.strip()
    if v.startswith('@color/'):
        return col(cmap[v[7:]], cmap, depth + 1)
    if v.startswith('@android:color/'):
        return {'white': ('#FFFFFF', 1), 'black': ('#000000', 1), 'transparent': ('#000000', 0)}[v[15:]]
    h = v.lstrip('#')
    if len(h) == 3: h = ''.join(c * 2 for c in h)
    if len(h) == 4: h = ''.join(c * 2 for c in h)
    if len(h) == 6: return '#' + h, 1.0
    if len(h) == 8: return '#' + h[2:], int(h[:2], 16) / 255
    raise ValueError(v)

class Conv:
    def __init__(self, res):
        self.res = res; self.cmap = colors(res); self.defs = []; self.n = 0

    def gradient(self, g):
        self.n += 1; gid = 'g%d' % self.n
        t = g.get(A + 'type', 'linear')
        stops = []
        items = list(g.iter('item'))
        if items:
            for it in items:
                c, a = col(it.get(A + 'color'), self.cmap)
                stops.append((float(it.get(A + 'offset')), c, a))
        else:
            for k, off in (('startColor', 0), ('centerColor', .5), ('endColor', 1)):
                if g.get(A + k):
                    c, a = col(g.get(A + k), self.cmap); stops.append((off, c, a))
        st = ''.join('<stop offset="%s" stop-color="%s" stop-opacity="%s"/>' % s for s in stops)
        if t == 'radial':
            self.defs.append('<radialGradient id="%s" gradientUnits="userSpaceOnUse" cx="%s" cy="%s" r="%s">%s</radialGradient>' % (
                gid, g.get(A + 'centerX'), g.get(A + 'centerY'), g.get(A + 'gradientRadius'), st))
        else:
            self.defs.append('<linearGradient id="%s" gradientUnits="userSpaceOnUse" x1="%s" y1="%s" x2="%s" y2="%s">%s</linearGradient>' % (
                gid, g.get(A + 'startX'), g.get(A + 'startY'), g.get(A + 'endX'), g.get(A + 'endY'), st))
        return 'url(#%s)' % gid

    def paint(self, el, attr):
        for a in el.findall(AAPT + 'attr'):
            if a.get('name') == 'android:' + attr:
                return self.gradient(a.find('gradient')), 1.0
        c = el.get(A + attr)
        if c is None: return None, 1.0
        return col(c, self.cmap)

    def node(self, el):
        tag = el.tag
        if tag == 'path':
            d = el.get(A + 'pathData')
            fill, fa = self.paint(el, 'fillColor')
            stroke, sa = self.paint(el, 'strokeColor')
            fa *= float(el.get(A + 'fillAlpha', 1)); sa *= float(el.get(A + 'strokeAlpha', 1))
            s = '<path d="%s" fill="%s" fill-opacity="%s" fill-rule="%s"' % (
                d, fill or 'none', fa, 'evenodd' if el.get(A + 'fillType') == 'evenOdd' else 'nonzero')
            if stroke:
                s += ' stroke="%s" stroke-opacity="%s" stroke-width="%s" stroke-linecap="%s" stroke-linejoin="%s"' % (
                    stroke, sa, el.get(A + 'strokeWidth', '0'), el.get(A + 'strokeLineCap', 'butt'), el.get(A + 'strokeLineJoin', 'miter'))
            return s + '/>'
        if tag == 'group':
            tx, ty = float(el.get(A + 'translateX', 0)), float(el.get(A + 'translateY', 0))
            sx, sy = float(el.get(A + 'scaleX', 1)), float(el.get(A + 'scaleY', 1))
            r = float(el.get(A + 'rotation', 0)); px, py = float(el.get(A + 'pivotX', 0)), float(el.get(A + 'pivotY', 0))
            # Android: translate(tx+px, ty+py) rotate(r) scale(sx,sy) translate(-px,-py)
            tr = 'translate(%s %s) rotate(%s) scale(%s %s) translate(%s %s)' % (tx + px, ty + py, r, sx, sy, -px, -py)
            inner = ''; clip = None
            for ch in el:
                if ch.tag == 'clip-path':
                    self.n += 1; clip = 'c%d' % self.n
                    self.defs.append('<clipPath id="%s"><path d="%s"/></clipPath>' % (clip, ch.get(A + 'pathData')))
                else:
                    x = self.node(ch)
                    if x: inner += x
            if clip: inner = '<g clip-path="url(#%s)">%s</g>' % (clip, inner)
            return '<g transform="%s">%s</g>' % (tr, inner)
        return ''

    def vector(self, path):
        root = ET.parse(path).getroot()
        if root.tag == 'vector':
            vw, vh = float(root.get(A + 'viewportWidth')), float(root.get(A + 'viewportHeight'))
            w = float(root.get(A + 'width').replace('dp', ''))
            body = ''; clip = None
            for ch in root:
                if ch.tag == 'clip-path':
                    self.n += 1; clip = 'c%d' % self.n
                    self.defs.append('<clipPath id="%s"><path d="%s"/></clipPath>' % (clip, ch.get(A + 'pathData')))
                else: body += self.node(ch)
            if clip: body = '<g clip-path="url(#%s)">%s</g>' % (clip, body)
            # scale viewport to 108 dp space
            return '<g transform="scale(%s %s)">%s</g>' % (108 / vw * (w / 108), 108 / vh * (w / 108), body)
        if root.tag == 'inset':
            ins = float(root.get(A + 'inset', '0').replace('%', '')) / 100 if '%' in root.get(A + 'inset', '') else 0
            d = root.get(A + 'drawable')
            inner = self.ref(d)
            s = 1 - 2 * ins
            return '<g transform="translate(%s %s) scale(%s)">%s</g>' % (108 * ins, 108 * ins, s, inner)
        raise ValueError(root.tag)

    def ref(self, d):
        if d.startswith('@color/') or d.startswith('#'):
            c, a = col(d, self.cmap)
            return '<rect width="108" height="108" fill="%s" fill-opacity="%s"/>' % (c, a)
        name = d.split('/')[-1]
        for sub in ('drawable', 'drawable-anydpi-v26', 'drawable-v24', 'mipmap-anydpi-v26'):
            p = os.path.join(self.res, sub, name + '.xml')
            if os.path.exists(p): return self.vector(p)
        raise FileNotFoundError(d)

def adaptive(res, icon_xml):
    c = Conv(res)
    root = ET.parse(icon_xml).getroot()
    bg = root.find('background').get(A + 'drawable'); fg = root.find('foreground').get(A + 'drawable')
    body = c.ref(bg) + c.ref(fg)
    # Visible area of an adaptive icon: the middle 72 dp, shown in a circle
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="168" height="168" viewBox="18 18 72 72"><defs>%s'
            '<clipPath id="mask"><circle cx="54" cy="54" r="36"/></clipPath></defs><g clip-path="url(#mask)">%s</g></svg>') % (''.join(c.defs), body)


# ---------------------------------------------------------------------------------------------------
# Refreshes the icons bundled in Komora (shown for apps not installed yet) from each app's repository.
# Run from the repository root:  python3 scripts/app_icons.py
# Needs git and rsvg-convert. Installed apps always show their own, current icon; this keeps the
# bundled copies current too. Review the pictures, then commit them with the next release.
import json, re, subprocess, tempfile

def launcher_xml(repo_dir):
    manifest = open(os.path.join(repo_dir, 'app/src/main/AndroidManifest.xml')).read()
    name = re.search(r'<application[^>]*android:icon="@mipmap/([^"]+)"', manifest, re.S).group(1)
    res = os.path.join(repo_dir, 'app/src/main/res')
    return res, os.path.join(res, 'mipmap-anydpi-v26', name + '.xml')

def render(res, icon_xml, out_png):
    svg = adaptive(res, icon_xml)
    with tempfile.NamedTemporaryFile('w', suffix='.svg', delete=False) as f:
        f.write(svg)
    subprocess.run(['rsvg-convert', '-w', '168', '-h', '168', f.name, '-o', out_png], check=True)
    os.unlink(f.name)

def main():
    catalog = json.load(open('app/src/main/assets/catalog.json'))
    out_dir = 'app/src/main/res/drawable-nodpi'
    with tempfile.TemporaryDirectory() as tmp:
        for app in catalog['apps']:
            out = os.path.join(out_dir, 'app_icon_%s.png' % app['assetPrefix'])
            if app['certSha256'] == 'self':
                repo_dir = '.'
            else:
                repo_dir = os.path.join(tmp, app['assetPrefix'])
                subprocess.run(['git', 'clone', '-q', '--depth', '1', 'https://github.com/%s' % app['repo'], repo_dir], check=True)
            res, xml = launcher_xml(repo_dir)
            render(res, xml, out)
            print('updated', out)

if __name__ == '__main__':
    main()
