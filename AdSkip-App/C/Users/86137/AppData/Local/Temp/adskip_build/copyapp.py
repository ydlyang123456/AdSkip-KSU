import os, sys, shutil
app, work = sys.argv[1], sys.argv[2]
dirs = [("res", "res"), ("src", "src"), ("assets", "assets")]
for s, d in dirs:
    src = os.path.join(app, s)
    dst = os.path.join(work, d)
    if os.path.isdir(dst):
        shutil.rmtree(dst)
    shutil.copytree(src, dst)
for f in ["AndroidManifest.xml", "assemble.py"]:
    shutil.copy(os.path.join(app, f), os.path.join(work, f))
print("copied sources from", app)
