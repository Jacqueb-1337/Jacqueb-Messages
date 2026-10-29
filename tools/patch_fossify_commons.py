"""Reproducibly rebrand Fossify Commons 7.1.3's false modded-app warning.

Android's actual package/signature validation is unaffected. The dependency's
BaseSimpleActivity.onCreate assumes only org.fossify.* packages are legitimate,
then displays a warning at random (~1/51 launches) or on every 100th launch.
That claim is false for our independently branded GPL-based Messages fork.

Usage: py tools/patch_fossify_commons.py [original.aar] [original.pom]
Original inputs must be the authentic Maven org.fossify:commons:7.1.3 artifacts.
"""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from io import BytesIO
import hashlib
import sys
import glob

VERSION="7.1.3"
LOCAL_VERSION="7.1.3-jacqueb.1"
EXPECTED_SHA256="4e421d32480e6a442b40789c24da9a5e7ffbf587ec939d5b81cb3da69237d0b5"
CLASSES={
    "org/fossify/commons/activities/BaseSimpleActivity.class":1,
    "org/fossify/commons/compose/extensions/ActivityExtensionsKt.class":1,
    "org/fossify/commons/compose/extensions/ComposeActivityExtensionsKt.class":4,
}
ORIGINAL=b"org.fossify."
BRANDED=b"com.jacqueb."
assert len(ORIGINAL)==len(BRANDED)


def write_zip(entries, out):
    with ZipFile(out,"w") as result:
        for info,data in entries:
            result.writestr(info,data)


def main():
    cache=Path.home()/".gradle/caches/modules-2/files-2.1/org.fossify/commons/7.1.3"
    aar=Path(sys.argv[1]) if len(sys.argv)>1 else Path(glob.glob(str(cache/"**/*.aar"),recursive=True)[0])
    pom=Path(sys.argv[2]) if len(sys.argv)>2 else Path(glob.glob(str(cache/"**/*.pom"),recursive=True)[0])
    actual=hashlib.sha256(aar.read_bytes()).hexdigest()
    if actual != EXPECTED_SHA256:
        raise ValueError(f"Unexpected Commons AAR hash {actual}; review upstream library before patching")
    with ZipFile(aar) as src:
        with ZipFile(BytesIO(src.read("classes.jar"))) as jar:
            entries=[]
            found=set()
            for info in jar.infolist():
                data=jar.read(info.filename)
                if info.filename in CLASSES:
                    count=data.count(ORIGINAL)
                    if count!=CLASSES[info.filename]:
                        raise ValueError(f"Unexpected package prefix count {info.filename}: {count}")
                    data=data.replace(ORIGINAL,BRANDED)
                    found.add(info.filename)
                entries.append((info,data))
            if found != set(CLASSES): raise ValueError(f"Missing expected class files: {set(CLASSES)-found}")
        jar_buffer=BytesIO()
        write_zip(entries,jar_buffer)
        outer=[(info,jar_buffer.getvalue() if info.filename=="classes.jar" else src.read(info.filename))
               for info in src.infolist()]
    output=Path("vendor/maven/org/fossify/commons")/LOCAL_VERSION
    output.mkdir(parents=True,exist_ok=True)
    target=output/f"commons-{LOCAL_VERSION}.aar"
    write_zip(outer,target)
    xml=pom.read_text(encoding="utf-8")
    expected=f"<version>{VERSION}</version>"
    if xml.count(expected)!=1: raise ValueError("Unexpected Commons POM coordinates")
    xml=xml.replace(expected,f"<version>{LOCAL_VERSION}</version>",1)
    xml=xml.replace("<!-- do_not_remove: published-with-gradle-metadata -->", "<!-- Local GPLv3 fork: Jacqueb Messages brand-specific false warning disabled -->")
    (output/f"commons-{LOCAL_VERSION}.pom").write_text(xml,encoding="utf-8",newline="\n")
    print(f"Patched Commons AAR: {target} ({target.stat().st_size:,} bytes)")
    print(f"SHA256: {hashlib.sha256(target.read_bytes()).hexdigest()}")

if __name__=="__main__": main()
