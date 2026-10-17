#!/bin/sh
# Builds ashavol.apk without Gradle. Run from this directory.
set -e
SDK=/c/Users/pintes/scoop/apps/android-clt/current
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar

rm -rf out && mkdir -p out/classes out/dex
javac -source 11 -target 11 -classpath "$JAR" -d out/classes $(find src -name '*.java')
"$BT/d8.bat" --min-api 33 --lib "$JAR" --output out/dex $(find out/classes -name '*.class')
"$BT/aapt2.exe" link -o out/base.apk --manifest AndroidManifest.xml -I "$JAR"
(cd out/dex && jar uf ../base.apk classes.dex)
"$BT/zipalign.exe" -f 4 out/base.apk out/aligned.apk
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias debug -keyalg RSA -validity 10000 -dname "CN=debug"
fi
"$BT/apksigner.bat" sign --ks debug.keystore --ks-pass pass:android --out ashavol.apk out/aligned.apk
echo built ashavol.apk
