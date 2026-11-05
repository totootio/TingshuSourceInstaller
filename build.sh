#!/usr/bin/env bash
# 手动构建脚本（不依赖 Gradle / Android Studio）
# Ubuntu 22.04 + JDK 20 + build-tools 34.0.0 + platform android-34
# v12: 批量导入 + 全宽圆角按钮 + 自带 App 图标（res/mipmap-*）
set -euo pipefail

ANDK="${ANDK:-/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d64-8ca96f81b000/andk}"
BT="$ANDK/bt/android-14"
ANDROID_JAR="$ANDK/pl/android-34-ext12/android.jar"
PROJ="$(cd "$(dirname "$0")" && pwd)"
SRC="$PROJ/app/src/main"
OUT="$PROJ/build"
FINAL_APK="${FINAL_APK:-/workspace/TingshuSourceInstaller.apk}"
TARGET_SDK="${TARGET_SDK:-29}"
KS="$PROJ/ts.keystore"

for f in "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$BT/zipalign" "$ANDROID_JAR"; do
  [ -e "$f" ] || { echo "缺少: $f"; echo "请先下载 build-tools_r34-linux.zip 与 platform-34 并解压到 $ANDK"; exit 1; }
done

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "[1/8] aapt2 compile res -> res_flat/"
mkdir -p "$OUT/res_flat"
mapfile -t RES_FILES < <(find "$SRC/res" -type f)
"$BT/aapt2" compile -o "$OUT/res_flat" "${RES_FILES[@]}" 2> "$OUT/aapt2c.log" \
  || { echo "aapt2 compile 失败:"; cat "$OUT/aapt2c.log"; exit 1; }

echo "[2/8] aapt2 link (资源 + 未签名 APK 骨架), targetSdk=$TARGET_SDK"
sed "s/android:targetSdkVersion=\"[0-9]*\"/android:targetSdkVersion=\"$TARGET_SDK\"/" \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  $(find "$OUT/res_flat" -name '*.flat') \
  --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version "$TARGET_SDK" \
  --version-code 14 --version-name 12.1 \
  --java "$OUT/gen"

echo "[3/8] javac 编译 Java 源码（仅 framework API）"
find "$SRC/java" -name '*.java' > "$OUT/sources.txt"
if ! javac -source 8 -target 8 -encoding UTF-8 -nowarn \
  -cp "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt" 2> "$OUT/javac.log"; then
  echo "javac 编译失败:"; cat "$OUT/javac.log"; exit 1
fi
grep -v -E 'bootstrap class path|source value 8|target value 8|deprecated' "$OUT/javac.log" || true

echo "[4/8] 准备 dex 输入"
find "$OUT/classes" -name 'module-info.class' -delete 2>/dev/null || true

echo "[5/8] d8 转换为 classes.dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt"
[ -f "$OUT/dex/classes.dex" ] || { echo "d8 未生成 classes.dex"; exit 1; }

echo "[6/8] 将 classes.dex 写入 APK"
( cd "$OUT/dex" && zip -q -0 "$OUT/base.apk" classes.dex )

echo "[7/8] zipalign + 签名 (v1+v2+v3)"
"$BT/zipalign" -p -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -alias tskey -keyalg RSA -keysize 2048 -validity 10000 \
    -keystore "$KS" -storepass ts123456 -keypass ts123456 \
    -dname "CN=TingshuSourceInstaller, OU=Dev, O=Personal, L=CN, ST=CN, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:ts123456 --key-pass pass:ts123456 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$FINAL_APK" "$OUT/aligned.apk"

echo "[8/8] 校验签名"
"$BT/apksigner" verify --verbose --print-certs "$FINAL_APK" | head -12

echo
echo "完成: $FINAL_APK"
ls -lh "$FINAL_APK"
