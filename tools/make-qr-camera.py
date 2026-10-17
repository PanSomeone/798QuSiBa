# Make a Y4M the Chromium fake camera can play: a QR code on a plain background.
# Y4M is the simplest video there is -- a text header, then per frame "FRAME\n"
# followed by raw YUV420 planes. No library, no container.
import qrcode, io
from PIL import Image

W, H = 640, 480
payload = "https://i.ilife798.com/q/1/aBcDeF123456"
qr = qrcode.make(payload).resize((360, 360)).convert("RGB")
canvas = Image.new("RGB", (W, H), (245, 245, 245))
canvas.paste(qr, ((W - 360) // 2, (H - 360) // 2))

yuv = canvas.convert("YCbCr")
y, cb, cr = yuv.split()
cb = cb.resize((W // 2, H // 2), Image.BILINEAR)
cr = cr.resize((W // 2, H // 2), Image.BILINEAR)

frames = int(__import__("sys").argv[1]) if len(__import__("sys").argv) > 1 else 40
out = r"D:\798QuSiBa\798QuSiBa\tools\qr-camera.y4m"
with open(out, "wb") as fh:
    fh.write(b"YUV4MPEG2 W%d H%d F10:1 Ip A1:1 C420jpeg\n" % (W, H))
    for _ in range(frames):
        fh.write(b"FRAME\n")
        fh.write(y.tobytes())
        fh.write(cb.tobytes())
        fh.write(cr.tobytes())
print("wrote", out, __import__("os").path.getsize(out), "bytes,", frames, "frames")