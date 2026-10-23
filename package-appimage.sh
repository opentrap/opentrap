#!/usr/bin/env bash
# Build a single-file, self-contained OpenTRAP AppImage for Linux.
#
# The result (dist/opentrap-x86_64.appimage) bundles a Java runtime and the scan
# engine, so a user downloads one file and runs it, with nothing installed, no
# Java, no terminal. It works across distributions.
#
# Needs: a JDK (for jpackage + jdeps), and internet the first time (to fetch
# appimagetool). Produces the AppImage from the self-contained release jar.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

# The AppImage runtime must be built from the pinned portable JDK (old glibc),
# or the packaged app only starts on machines as new as the build host (it quits
# before showing a window on older distributions). Fetch it if missing, then use
# it for the release build below and for jlink, jpackage and the module list.
[ -x "$DIR/jdk/bin/javac" ] || ./fetch-jdk.sh
export JAVA_HOME="$DIR/jdk"
export PATH="$DIR/jdk/bin:$PATH"

echo ">> building the self-contained release jar"
./release.sh
JAR="$(ls -t dist/opentrap_*.jar | head -1)"
JARNAME="$(basename "$JAR")"

AIT=/tmp/appimagetool
if [ ! -x "$AIT" ]; then
    echo ">> fetching appimagetool"
    curl -sSL -o "$AIT" "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
    chmod +x "$AIT"
fi

echo ">> assembling a portable Java runtime from the bundled JDK"
rm -rf build/otin build/appimage build/runtime AppDir
mkdir -p build/otin
cp "$JAR" build/otin/
# The runtime must keep bin/java and a full module set: the bundled scan engine
# runs as its own Java process (launched with java.home/bin/java), and jpackage's
# default runtime strips bin/java and trims modules, which breaks it. The portable
# Temurin JDK (old glibc) ships without jmods, so jlink cannot repackage it; copy
# it as the runtime image instead and drop only parts not needed at run time.
cp -a jdk build/runtime
rm -rf build/runtime/lib/src.zip build/runtime/include build/runtime/man \
       build/runtime/demo build/runtime/sample build/runtime/jmods

echo ">> jpackage: self-contained app image using that runtime"
jpackage --type app-image --name OpenTRAP \
    --input build/otin --main-jar "$JARNAME" --main-class opentrap.ui.Main \
    --icon src/opentrap/ui/icons/opentrap_256.png \
    --runtime-image build/runtime --java-options "-XX:MaxRAMPercentage=50" \
    --dest build/appimage

echo ">> assembling AppDir (with a scale-probing launcher)"
rm -rf AppDir
mkdir -p AppDir/usr
cp -a build/appimage/OpenTRAP/. AppDir/usr/
cp src/opentrap/ui/icons/opentrap_256.png AppDir/opentrap.png
# The launcher picks a HiDPI scale with a quick headless probe and sets it before
# the real JVM starts. This matters inside an AppImage: the in-app relaunch the
# plain jar uses to apply the scale would tear down the AppImage mount and kill
# the app, so here the scale is chosen up front instead.
cat > AppDir/AppRun <<'RUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
SCALE="$("$HERE/usr/bin/OpenTRAP" --print-scale 2>/dev/null | tr -d '[:space:]')"
case "$SCALE" in
  1.25|1.5|2|2.0|3|3.0) export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dsun.java2d.uiScale=$SCALE -Dopentrap.scaled=$SCALE" ;;
esac
exec "$HERE/usr/bin/OpenTRAP" "$@"
RUN
chmod +x AppDir/AppRun
cat > AppDir/OpenTRAP.desktop <<'DESK'
[Desktop Entry]
Type=Application
Name=OpenTRAP
Comment=Open web security testing proxy
Exec=OpenTRAP
Icon=opentrap
Categories=Development;Network;Security;
Terminal=false
DESK

echo ">> packing the AppImage"
ARCH=x86_64 "$AIT" --appimage-extract-and-run AppDir dist/opentrap-x86_64.appimage

echo
echo "Built dist/opentrap-x86_64.appimage"
echo "Run it with:  chmod +x dist/opentrap-x86_64.appimage && ./dist/opentrap-x86_64.appimage"
